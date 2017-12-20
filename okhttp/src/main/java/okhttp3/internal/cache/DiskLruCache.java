/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.cache;

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.Flushable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.NoSuchElementException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import okhttp3.internal.Util;
import okhttp3.internal.io.FileSystem;
import okhttp3.internal.platform.Platform;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;
import okio.Sink;
import okio.Source;

import static okhttp3.internal.platform.Platform.WARN;

/**
 * A cache that uses a bounded amount of space on a filesystem. Each cache entry has a string key
 * and a fixed number of values. Each key must match the regex <strong>[a-z0-9_-]{1,64}</strong>.
 * Values are byte sequences, accessible as streams or files. Each value must be between {@code 0}
 * and {@code Integer.MAX_VALUE} bytes in length.
 *
 * <p>The cache stores its data in a directory on the filesystem. This directory must be exclusive
 * to the cache; the cache may delete or overwrite files from its directory. It is an error for
 * multiple processes to use the same cache directory at the same time.
 *
 * <p>This cache limits the number of bytes that it will store on the filesystem. When the number of
 * stored bytes exceeds the limit, the cache will remove entries in the background until the limit
 * is satisfied. The limit is not strict: the cache may temporarily exceed it while waiting for
 * files to be deleted. The limit does not include filesystem overhead or the cache journal so
 * space-sensitive applications should set a conservative limit.
 *
 * <p>Clients call {@link #edit} to create or update the values of an entry. An entry may have only
 * one editor at one time; if a value is not available to be edited then {@link #edit} will return
 * null.
 *
 * <ul>
 *     <li>When an entry is being <strong>created</strong> it is necessary to supply a full set of
 *         values; the empty value should be used as a placeholder if necessary.
 *     <li>When an entry is being <strong>edited</strong>, it is not necessary to supply data for
 *         every value; values default to their previous value.
 * </ul>
 *
 * <p>Every {@link #edit} call must be matched by a call to {@link Editor#commit} or {@link
 * Editor#abort}. Committing is atomic: a read observes the full set of values as they were before
 * or after the commit, but never a mix of values.
 *
 * <p>Clients call {@link #get} to read a snapshot of an entry. The read will observe the value at
 * the time that {@link #get} was called. Updates and removals after the call do not impact ongoing
 * reads.
 *
 * <p>This class is tolerant of some I/O errors. If files are missing from the filesystem, the
 * corresponding entries will be dropped from the cache. If an error occurs while writing a cache
 * value, the edit will fail silently. Callers should handle other problems by catching {@code
 * IOException} and responding appropriately.
 */
public final class DiskLruCache implements Closeable, Flushable {
  static final String JOURNAL_FILE = "journal";
  static final String JOURNAL_FILE_TEMP = "journal.tmp";
  static final String JOURNAL_FILE_BACKUP = "journal.bkp";
  static final String MAGIC = "libcore.io.DiskLruCache";
  static final String VERSION_1 = "1";
  static final long ANY_SEQUENCE_NUMBER = -1;
  static final Pattern LEGAL_KEY_PATTERN = Pattern.compile("[a-z0-9_-]{1,120}");
  // 每一个Cache项对应两个状态副本：DIRTY，CLEAN。
  // CLEAN表示当前可用的Cache。外部访问到cache快照均为CLEAN状态；
  // DIRTY为编辑状态的cache。由于更新和创新都只操作DIRTY状态的副本，
  // 实现了读和写的分离。
  private static final String CLEAN = "CLEAN"; // 写入完成
  private static final String DIRTY = "DIRTY"; // 正在写入
  private static final String REMOVE = "REMOVE";
  private static final String READ = "READ"; // 已被读过了

    /*
     * This cache uses a journal file named "journal". A typical journal file
     * looks like this:
     *     libcore.io.DiskLruCache
     *     1
     *     100
     *     2
     *
     *     CLEAN 3400330d1dfc7f3f7f4b8d4d803dfcf6 832 21054
     *     DIRTY 335c4c6028171cfddfbaae1a9c313c52
     *     CLEAN 335c4c6028171cfddfbaae1a9c313c52 3934 2342
     *     REMOVE 335c4c6028171cfddfbaae1a9c313c52
     *     DIRTY 1ab96a171faeeee38496d8b330771a7a
     *     CLEAN 1ab96a171faeeee38496d8b330771a7a 1600 234
     *     READ 335c4c6028171cfddfbaae1a9c313c52
     *     READ 3400330d1dfc7f3f7f4b8d4d803dfcf6
     *
     * The first five lines of the journal form its header. They are the
     * constant string "libcore.io.DiskLruCache", the disk cache's version,
     * the application's version, the value count, and a blank line.
     *
     * Each of the subsequent lines in the file is a record of the state of a
     * cache entry. Each line contains space-separated values: a state, a key,
     * and optional state-specific values.
     *   o DIRTY lines track that an entry is actively being created or updated.
     *     Every successful DIRTY action should be followed by a CLEAN or REMOVE
     *     action. DIRTY lines without a matching CLEAN or REMOVE indicate that
     *     temporary files may need to be deleted.
     *   o CLEAN lines track a cache entry that has been successfully published
     *     and may be read. A publish line is followed by the lengths of each of
     *     its values.
     *   o READ lines track accesses for LRU.
     *   o REMOVE lines track entries that have been deleted.
     *
     * The journal file is appended to as cache operations occur. The journal may
     * occasionally be compacted by dropping redundant lines. A temporary file named
     * "journal.tmp" will be used during compaction; that file should be deleted if
     * it exists when the cache is opened.
     */

  final FileSystem fileSystem;
  final File directory;
  private final File journalFile;
  private final File journalFileTmp;
  private final File journalFileBackup;
  private final int appVersion;
  private long maxSize;
  final int valueCount;
  private long size = 0;
  BufferedSink journalWriter;
  final LinkedHashMap<String, Entry> lruEntries = new LinkedHashMap<>(0, 0.75f, true);
  int redundantOpCount;
  boolean hasJournalErrors;

  // Must be read and written when synchronized on 'this'.
  boolean initialized;
  boolean closed;
  boolean mostRecentTrimFailed; // 最近的一次 Trim 失败
  boolean mostRecentRebuildFailed;

  /**
   * To differentiate between old and current snapshots, each entry is given a sequence number each
   * time an edit is committed. A snapshot is stale if its sequence number is not equal to its
   * entry's sequence number.
   */
  private long nextSequenceNumber = 0;

  /**
   * 后台线程，用户清理工作
   * Used to run 'cleanupRunnable' for journal rebuilds.
   */
  private final Executor executor;

  private final Runnable cleanupRunnable = new Runnable() {
    public void run() {
      synchronized (DiskLruCache.this) {
        if (!initialized | closed) {
          return; // Nothing to do
        }

        try {
          // 调整整个cache的大小，以防止它过大,这里面做了remove的操作
          trimToSize();
        } catch (IOException ignored) {
          // 如果抛异常了，设置最近的一次清理失败
          mostRecentTrimFailed = true;
        }

        try {
          // Rebuild journal
          if (journalRebuildRequired()) {
            rebuildJournal();
            redundantOpCount = 0;
          }
        } catch (IOException e) {
          mostRecentRebuildFailed = true;
          journalWriter = Okio.buffer(Okio.blackhole());
        }
      }
    }
  };

  DiskLruCache(FileSystem fileSystem, File directory, int appVersion, int valueCount, long maxSize,
      Executor executor) {
    this.fileSystem = fileSystem;
    this.directory = directory;
    this.appVersion = appVersion;
    this.journalFile = new File(directory, JOURNAL_FILE);
    this.journalFileTmp = new File(directory, JOURNAL_FILE_TEMP);
    this.journalFileBackup = new File(directory, JOURNAL_FILE_BACKUP);
    this.valueCount = valueCount;
    this.maxSize = maxSize;
    this.executor = executor;
  }

  /**
   * 初始化的方法
   * @throws IOException
   */
  public synchronized void initialize() throws IOException {
    assert Thread.holdsLock(this);

    if (initialized) {
      return; // Already initialized.
    }

    // If a bkp file exists, use it instead.
    if (fileSystem.exists(journalFileBackup)) {
      // If journal file also exists just delete backup file.
      if (fileSystem.exists(journalFile)) {
        fileSystem.delete(journalFileBackup);
      } else {
        fileSystem.rename(journalFileBackup, journalFile);
      }
    }

    // Prefer to pick up where we left off.
    if (fileSystem.exists(journalFile)) {// 文件存在的话，恢复现场
      try {
        readJournal();
        processJournal();
        initialized = true;
        return;
      } catch (IOException journalIsCorrupt) {
        Platform.get().log(WARN, "DiskLruCache " + directory + " is corrupt: "
            + journalIsCorrupt.getMessage() + ", removing", journalIsCorrupt);
      }

      // The cache is corrupted, attempt to delete the contents of the directory. This can throw and
      // we'll let that propagate out as it likely means there is a severe filesystem problem.
      // 初始化不成功才会运行这个方法
      // 缓存已损坏，尝试删除目录的内容,这里我们需要将异常抛出去，因为这可能意味着存在严重的文件系统问题。
      try {
        delete();
      } finally {
        closed = false;
      }
    }
    // 文件不存在，或则上面的步骤抛出了异常
    rebuildJournal();

    initialized = true;
  }

  /**
   * Create a cache which will reside in {@code directory}. This cache is lazily initialized on
   * first access and will be created if it does not exist.
   *
   * @param directory a writable directory
   * @param valueCount the number of values per cache entry. Must be positive.
   * @param maxSize the maximum number of bytes this cache should use to store
   */
  public static DiskLruCache create(FileSystem fileSystem, File directory, int appVersion,
      int valueCount, long maxSize) {
    if (maxSize <= 0) {
      throw new IllegalArgumentException("maxSize <= 0");
    }
    if (valueCount <= 0) {
      throw new IllegalArgumentException("valueCount <= 0");
    }

    // Use a single background thread to evict entries.

    Executor executor = new ThreadPoolExecutor(0, 1, 60L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<Runnable>(), Util.threadFactory("OkHttp DiskLruCache", true));

    return new DiskLruCache(fileSystem, directory, appVersion, valueCount, maxSize, executor);
  }

  /**
   *

   libcore.io.DiskLruCache
   1
   100
   2

   CLEAN 3400330d1dfc7f3f7f4b8d4d803dfcf6 832 21054
   DIRTY 335c4c6028171cfddfbaae1a9c313c52
   CLEAN 335c4c6028171cfddfbaae1a9c313c52 3934 2342
   REMOVE 335c4c6028171cfddfbaae1a9c313c52
   DIRTY 1ab96a171faeeee38496d8b330771a7a
   CLEAN 1ab96a171faeeee38496d8b330771a7a 1600 234
   READ 335c4c6028171cfddfbaae1a9c313c52


   * @throws IOException
   */
  private void readJournal() throws IOException {
    BufferedSource source = Okio.buffer(fileSystem.source(journalFile));
    try {
      String magic = source.readUtf8LineStrict();
      String version = source.readUtf8LineStrict();
      String appVersionString = source.readUtf8LineStrict();
      String valueCountString = source.readUtf8LineStrict();
      String blank = source.readUtf8LineStrict();
      if (!MAGIC.equals(magic)
          || !VERSION_1.equals(version)
          || !Integer.toString(appVersion).equals(appVersionString)
          || !Integer.toString(valueCount).equals(valueCountString)
          || !"".equals(blank)) {
        throw new IOException("unexpected journal header: [" + magic + ", " + version + ", "
            + valueCountString + ", " + blank + "]");
      }

      int lineCount = 0;
      // 逐行读取数据
      while (true) {
        try {
          readJournalLine(source.readUtf8LineStrict());
          lineCount++;
        } catch (EOFException endOfJournal) {
          break;
        }
      }
      // 读取出来的行数减去lruEntriest的集合的差值，即日志多出的"冗余"记录
      redundantOpCount = lineCount - lruEntries.size();

      // If we ended on a truncated line, rebuild the journal before appending to it.
      // 如果我们将一个line end，重新创建这个journal文件
      if (!source.exhausted()) { // exhausted 是否还多余字节，如果没有多余字节，返回true，有多月字节返回false
        // 重新构建下journal文件
        rebuildJournal();
      } else {
        // 获取这个文件的Sink
        journalWriter = newJournalWriter();
      }
    } finally {
      Util.closeQuietly(source);
    }
  }

  private BufferedSink newJournalWriter() throws FileNotFoundException {
    Sink fileSink = fileSystem.appendingSink(journalFile);
    Sink faultHidingSink = new FaultHidingSink(fileSink) {
      @Override protected void onException(IOException e) {
        assert (Thread.holdsLock(DiskLruCache.this));
        hasJournalErrors = true;
      }
    };
    return Okio.buffer(faultHidingSink);
  }

  /**


   libcore.io.DiskLruCache
   1
   100
   2

   CLEAN 3400330d1dfc7f3f7f4b8d4d803dfcf6 832 21054
   DIRTY 335c4c6028171cfddfbaae1a9c313c52
   CLEAN 335c4c6028171cfddfbaae1a9c313c52 3934 2342
   REMOVE 335c4c6028171cfddfbaae1a9c313c52
   DIRTY 1ab96a171faeeee38496d8b330771a7a
   CLEAN 1ab96a171faeeee38496d8b330771a7a 1600 234
   READ 335c4c6028171cfddfbaae1a9c313c52

   解析Journal里面的文件，读取里面的内容并将内容赋值给lruEntries里面的entry
   * @param line
   * @throws IOException
   */
  private void readJournalLine(String line) throws IOException {
    int firstSpace = line.indexOf(' ');
    if (firstSpace == -1) {
      throw new IOException("unexpected journal line: " + line);
    }

    int keyBegin = firstSpace + 1;
    int secondSpace = line.indexOf(' ', keyBegin);
    final String key;
    if (secondSpace == -1) {
      key = line.substring(keyBegin);
      if (firstSpace == REMOVE.length() && line.startsWith(REMOVE)) {
        lruEntries.remove(key);
        return;
      }
    } else {
      key = line.substring(keyBegin, secondSpace);
    }

    Entry entry = lruEntries.get(key);
    if (entry == null) {
      entry = new Entry(key);
      lruEntries.put(key, entry);
    }

    if (secondSpace != -1 && firstSpace == CLEAN.length() && line.startsWith(CLEAN)) {
      String[] parts = line.substring(secondSpace + 1).split(" ");
      entry.readable = true;
      entry.currentEditor = null;
      entry.setLengths(parts);
    } else if (secondSpace == -1 && firstSpace == DIRTY.length() && line.startsWith(DIRTY)) {
      entry.currentEditor = new Editor(entry);
    } else if (secondSpace == -1 && firstSpace == READ.length() && line.startsWith(READ)) {
      // This work was already done by calling lruEntries.get().
    } else {
      throw new IOException("unexpected journal line: " + line);
    }
  }

  /**
   * clean dirty的entries所对应的文件全部删除，这里只是将文件删除，entry中还会保留一份
   * Computes the initial size and collects garbage as a part of opening the cache. Dirty entries
   * are assumed to be inconsistent and will be deleted.
   */
  private void processJournal() throws IOException {
    fileSystem.delete(journalFileTmp); // 删除临时文件
    for (Iterator<Entry> i = lruEntries.values().iterator(); i.hasNext(); ) {
      Entry entry = i.next();
      if (entry.currentEditor == null) {
        for (int t = 0; t < valueCount; t++) {
          size += entry.lengths[t];
        }
      } else {
        entry.currentEditor = null;
        for (int t = 0; t < valueCount; t++) {
          fileSystem.delete(entry.cleanFiles[t]);
          fileSystem.delete(entry.dirtyFiles[t]);
        }
        i.remove();
      }
    }
  }

  /**
   * 创建一个省略冗余信息 新的journal。 如果文件存在，就会替换当前的 journal 。
   * 这里会把dirty和clean的字段回写到journal当中，这里只是把记录重新写了一次
   * Creates a new journal that omits redundant information. This replaces the current journal if it
   * exists.
   */
  synchronized void rebuildJournal() throws IOException {
    if (journalWriter != null) {
      journalWriter.close();
    }
    // 写入 journal.tmp
    BufferedSink writer = Okio.buffer(fileSystem.sink(journalFileTmp));
    try {
      writer.writeUtf8(MAGIC).writeByte('\n');
      writer.writeUtf8(VERSION_1).writeByte('\n');
      writer.writeDecimalLong(appVersion).writeByte('\n');
      writer.writeDecimalLong(valueCount).writeByte('\n');
      writer.writeByte('\n');

      for (Entry entry : lruEntries.values()) {
        if (entry.currentEditor != null) {
          writer.writeUtf8(DIRTY).writeByte(' ');
          writer.writeUtf8(entry.key);
          writer.writeByte('\n');
        } else {
          writer.writeUtf8(CLEAN).writeByte(' ');
          writer.writeUtf8(entry.key);
          entry.writeLengths(writer);
          writer.writeByte('\n');
        }
      }
    } finally {
      writer.close();
    }

    if (fileSystem.exists(journalFile)) {
      // 原文件存在，先备份 journal --> journal.bkp
      fileSystem.rename(journalFile, journalFileBackup);
    }
    // journal.tmp --> journal
    fileSystem.rename(journalFileTmp, journalFile);
    // 删除 journal.bkp
    fileSystem.delete(journalFileBackup);

    journalWriter = newJournalWriter();
    hasJournalErrors = false;
    mostRecentRebuildFailed = false;
  }

  /**
   * Returns a snapshot of the entry named {@code key}, or null if it doesn't exist is not currently
   * readable. If a value is returned, it is moved to the head of the LRU queue.
   */
  public synchronized Snapshot get(String key) throws IOException {
    initialize();

    checkNotClosed();
    validateKey(key);
    Entry entry = lruEntries.get(key);
    if (entry == null || !entry.readable) return null;

    // 先拿到snapshot
    Snapshot snapshot = entry.snapshot();
    if (snapshot == null) return null;

    // 计数器自加1
    redundantOpCount++;
    // journalWriter向journal文件中写入一条READ记录
    journalWriter.writeUtf8(READ).writeByte(' ').writeUtf8(key).writeByte('\n');
    if (journalRebuildRequired()) { // 如果超过上限
      executor.execute(cleanupRunnable);
    }

    return snapshot;
  }

  /**
   * Returns an editor for the entry named {@code key}, or null if another edit is in progress.
   */
  public @Nullable Editor edit(String key) throws IOException {
    return edit(key, ANY_SEQUENCE_NUMBER);
  }

  synchronized Editor edit(String key, long expectedSequenceNumber) throws IOException {
    initialize();

    checkNotClosed();
    validateKey(key);
    Entry entry = lruEntries.get(key);
    if (expectedSequenceNumber != ANY_SEQUENCE_NUMBER && (entry == null
        || entry.sequenceNumber != expectedSequenceNumber)) {
      return null; // Snapshot is stale.
    }
    // 如果已经有别的editor在操作这个entry了，那就返回null
    if (entry != null && entry.currentEditor != null) {
      return null; // Another edit is in progress.
    }
    if (mostRecentTrimFailed || mostRecentRebuildFailed) {
      // The OS has become our enemy! If the trim job failed, it means we are storing more data than
      // requested by the user. Do not allow edits so we do not go over that limit any further. If
      // the journal rebuild failed, the journal writer will not be active, meaning we will not be
      // able to record the edit, causing file leaks. In both cases, we want to retry the clean up
      // so we can get out of this state!
      // OS已经成为我们的敌人，如果trim失败，意味着我们正在存储比用户请求更多的数据
      // 这个时候我们是不允许编辑的，所有我们不要超过这个限制，
      // 如果journal创建是吧，journal writer将不会被激活，这意味着我们将无法记录这个eidt，导致文件泄漏
      // 这种请求下，我们可以执行clean操作来摆脱这种状态
      executor.execute(cleanupRunnable);
      return null;
    }

    // Flush the journal before creating files to prevent file leaks.
    // 在创建文件前来清空journal来组织文件泄漏
    // 把当前的key在journal文件里标记成dirty状态，表示这条记录正在被修改
    journalWriter.writeUtf8(DIRTY).writeByte(' ').writeUtf8(key).writeByte('\n');
    journalWriter.flush(); // 调用edit以后就会写入dirty标志

    if (hasJournalErrors) {
      return null; // Don't edit; the journal can't be written.
    }

    if (entry == null) {
      entry = new Entry(key);
      lruEntries.put(key, entry);
    }
    Editor editor = new Editor(entry);
    entry.currentEditor = editor;
    return editor;
  }

  /** Returns the directory where this cache stores its data. */
  public File getDirectory() {
    return directory;
  }

  /**
   * Returns the maximum number of bytes that this cache should use to store its data.
   */
  public synchronized long getMaxSize() {
    return maxSize;
  }

  /**
   * Changes the maximum number of bytes the cache can store and queues a job to trim the existing
   * store, if necessary.
   */
  public synchronized void setMaxSize(long maxSize) {
    this.maxSize = maxSize;
    if (initialized) {
      executor.execute(cleanupRunnable);
    }
  }

  /**
   * Returns the number of bytes currently being used to store the values in this cache. This may be
   * greater than the max size if a background deletion is pending.
   */
  public synchronized long size() throws IOException {
    initialize();
    return size;
  }

  /**
   *
   * @param editor
   * @param success 否要写回entry里的数据
   * @throws IOException
   */
  synchronized void completeEdit(Editor editor, boolean success) throws IOException {
    Entry entry = editor.entry;
    if (entry.currentEditor != editor) {
      throw new IllegalStateException();
    }

    // If this edit is creating the entry for the first time, every index must have a value.
    // 第一次操作，保证每个index里面都有数据
    if (success && !entry.readable) {
      for (int i = 0; i < valueCount; i++) {
        if (!editor.written[i]) {
          editor.abort();
          throw new IllegalStateException("Newly created entry didn't create value for index " + i);
        }
        if (!fileSystem.exists(entry.dirtyFiles[i])) {
          editor.abort();
          return;
        }
      }
    }


    for (int i = 0; i < valueCount; i++) {
      File dirty = entry.dirtyFiles[i];
      if (success) {
        // 把dirtyFile重命名为cleanFile
        if (fileSystem.exists(dirty)) {
          File clean = entry.cleanFiles[i];
          fileSystem.rename(dirty, clean);
          long oldLength = entry.lengths[i];
          long newLength = fileSystem.size(clean);
          // 更新 entry.lengths[i]
          entry.lengths[i] = newLength;
          size = size - oldLength + newLength;
        }
      } else {
        //删除dirty数据
        fileSystem.delete(dirty);
      }
    }

    redundantOpCount++;
    // 将对应的操作写入到journal里
    entry.currentEditor = null;
    if (entry.readable | success) {
      entry.readable = true;
      journalWriter.writeUtf8(CLEAN).writeByte(' ');
      journalWriter.writeUtf8(entry.key);
      entry.writeLengths(journalWriter);
      journalWriter.writeByte('\n');
      if (success) {
        entry.sequenceNumber = nextSequenceNumber++;
      }
    } else {
      // 写入remove
      lruEntries.remove(entry.key);
      journalWriter.writeUtf8(REMOVE).writeByte(' ');
      journalWriter.writeUtf8(entry.key);
      journalWriter.writeByte('\n');
    }
    journalWriter.flush();

    // 如果commit次数很多的话，会尝试cleanup操作
    if (size > maxSize || journalRebuildRequired()) {
      executor.execute(cleanupRunnable);
    }
  }

  /**
   * We only rebuild the journal when it will halve the size of the journal and eliminate at least
   * 2000 ops.
   */
  boolean journalRebuildRequired() {
    // 最大计数单位
    final int redundantOpCompactThreshold = 2000;
    return redundantOpCount >= redundantOpCompactThreshold
        && redundantOpCount >= lruEntries.size();
  }

  /**
   * Drops the entry for {@code key} if it exists and can be removed. If the entry for {@code key}
   * is currently being edited, that edit will complete normally but its value will not be stored.
   *
   * @return true if an entry was removed.
   */
  public synchronized boolean remove(String key) throws IOException {
    initialize();

    checkNotClosed();
    validateKey(key);
    Entry entry = lruEntries.get(key);
    if (entry == null) return false;
    boolean removed = removeEntry(entry);
    if (removed && size <= maxSize) mostRecentTrimFailed = false;
    return removed;
  }

  /**
   * 移除entry，写入remove
   * @param entry
   * @return
   * @throws IOException
   */
  boolean removeEntry(Entry entry) throws IOException {
    if (entry.currentEditor != null) {
      // //让这个editor正常的结束
      entry.currentEditor.detach(); // Prevent the edit from completing normally.
    }


    for (int i = 0; i < valueCount; i++) {
      // 删除entry对应的clean文件
      fileSystem.delete(entry.cleanFiles[i]);
      // 缓存大小减去entry的小小
      size -= entry.lengths[i];
      // 设置entry的缓存为0
      entry.lengths[i] = 0;
    }

    // 计数器自加1
    redundantOpCount++;
    // 在journalWriter添加一条删除记录
    journalWriter.writeUtf8(REMOVE).writeByte(' ').writeUtf8(entry.key).writeByte('\n');
    lruEntries.remove(entry.key);

    if (journalRebuildRequired()) {
      executor.execute(cleanupRunnable);
    }

    return true;
  }

  /** Returns true if this cache has been closed. */
  public synchronized boolean isClosed() {
    return closed;
  }

  private synchronized void checkNotClosed() {
    if (isClosed()) {
      throw new IllegalStateException("cache is closed");
    }
  }

  /** Force buffered operations to the filesystem. */
  @Override public synchronized void flush() throws IOException {
    if (!initialized) return;

    checkNotClosed();
    trimToSize();
    journalWriter.flush();
  }

  /**
   * 关闭缓存，存储的值将保留在文件系统上。
   * Closes this cache. Stored values will remain on the filesystem.
   */
  @Override public synchronized void close() throws IOException {
    if (!initialized || closed) {
      closed = true;
      return;
    }
    // Copying for safe iteration.
    for (Entry entry : lruEntries.values().toArray(new Entry[lruEntries.size()])) {
      if (entry.currentEditor != null) {
        entry.currentEditor.abort();
      }
    }
    trimToSize();
    journalWriter.close();
    journalWriter = null;
    closed = true;
  }

  void trimToSize() throws IOException {
    while (size > maxSize) {
      // 删除 Entry
      Entry toEvict = lruEntries.values().iterator().next();
      removeEntry(toEvict);
    }
    mostRecentTrimFailed = false;
  }

  /**
   * 关闭缓存并且删除所有存储的值，这些将会删除缓存文件夹里面的所有的文件
   * Closes the cache and deletes all of its stored values. This will delete all files in the cache
   * directory including files that weren't created by the cache.
   */
  public void delete() throws IOException {
    close();
    fileSystem.deleteContents(directory);
  }

  /**
   * 从缓存中删除所有值，In-flight中的eidt将正常完成，但是值不会被存储
   * Deletes all stored values from the cache. In-flight edits will complete normally but their
   * values will not be stored.
   */
  public synchronized void evictAll() throws IOException {
    initialize();
    // Copying for safe iteration.
    for (Entry entry : lruEntries.values().toArray(new Entry[lruEntries.size()])) {
      removeEntry(entry);
    }
    mostRecentTrimFailed = false;
  }

  private void validateKey(String key) {
    Matcher matcher = LEGAL_KEY_PATTERN.matcher(key);
    if (!matcher.matches()) {
      throw new IllegalArgumentException(
          "keys must match regex [a-z0-9_-]{1,120}: \"" + key + "\"");
    }
  }

  /**
   * Returns an iterator over the cache's current entries. This iterator doesn't throw {@code
   * ConcurrentModificationException}, but if new entries are added while iterating, those new
   * entries will not be returned by the iterator. If existing entries are removed during iteration,
   * they will be absent (unless they were already returned).
   *
   * <p>If there are I/O problems during iteration, this iterator fails silently. For example, if
   * the hosting filesystem becomes unreachable, the iterator will omit elements rather than
   * throwing exceptions.
   *
   * <p><strong>The caller must {@link Snapshot#close close}</strong> each snapshot returned by
   * {@link Iterator#next}. Failing to do so leaks open files!
   *
   * <p>The returned iterator supports {@link Iterator#remove}.
   */
  public synchronized Iterator<Snapshot> snapshots() throws IOException {
    initialize();
    return new Iterator<Snapshot>() {
      /** Iterate a copy of the entries to defend against concurrent modification errors. */
      final Iterator<Entry> delegate = new ArrayList<>(lruEntries.values()).iterator();

      /** The snapshot to return from {@link #next}. Null if we haven't computed that yet. */
      Snapshot nextSnapshot;

      /** The snapshot to remove with {@link #remove}. Null if removal is illegal. */
      Snapshot removeSnapshot;

      @Override public boolean hasNext() {
        if (nextSnapshot != null) return true;

        synchronized (DiskLruCache.this) {
          // If the cache is closed, truncate the iterator.
          if (closed) return false;

          while (delegate.hasNext()) {
            Entry entry = delegate.next();
            Snapshot snapshot = entry.snapshot();
            if (snapshot == null) continue; // Evicted since we copied the entries.
            nextSnapshot = snapshot;
            return true;
          }
        }

        return false;
      }

      @Override public Snapshot next() {
        if (!hasNext()) throw new NoSuchElementException();
        removeSnapshot = nextSnapshot;
        nextSnapshot = null;
        return removeSnapshot;
      }

      @Override public void remove() {
        if (removeSnapshot == null) throw new IllegalStateException("remove() before next()");
        try {
          DiskLruCache.this.remove(removeSnapshot.key);
        } catch (IOException ignored) {
          // Nothing useful to do here. We failed to remove from the cache. Most likely that's
          // because we couldn't update the journal, but the cached entry will still be gone.
        } finally {
          removeSnapshot = null;
        }
      }
    };
  }

  /** A snapshot of the values for an entry. */
  public final class Snapshot implements Closeable {
    private final String key;
    private final long sequenceNumber; // 序列号
    private final Source[] sources; // 可以读入数据的流   这么多的流主要是从cleanFile中读取数据
    private final long[] lengths; // 与上面的流一一对应

    Snapshot(String key, long sequenceNumber, Source[] sources, long[] lengths) {
      this.key = key;
      this.sequenceNumber = sequenceNumber;
      this.sources = sources;
      this.lengths = lengths;
    }

    public String key() {
      return key;
    }

    /**
     *
     * Returns an editor for this snapshot's entry, or null if either the entry has changed since
     * this snapshot was created or if another edit is in progress.
     */
    public @Nullable Editor edit() throws IOException {
      return DiskLruCache.this.edit(key, sequenceNumber);
    }

    /** Returns the unbuffered stream with the value for {@code index}. */
    public Source getSource(int index) {
      return sources[index];
    }

    /** Returns the byte length of the value for {@code index}. */
    public long getLength(int index) {
      return lengths[index];
    }

    public void close() {
      for (Source in : sources) {
        Util.closeQuietly(in);
      }
    }
  }

  /** Edits the values for an entry. */
  public final class Editor {
    final Entry entry;
    final boolean[] written;
    private boolean done;

    Editor(Entry entry) {
      this.entry = entry;
      this.written = (entry.readable) ? null : new boolean[valueCount];
    }

    /**
     * Prevents this editor from completing normally. This is necessary either when the edit causes
     * an I/O error, or if the target entry is evicted while this editor is active. In either case
     * we delete the editor's created files and prevent new files from being created. Note that once
     * an editor has been detached it is possible for another editor to edit the entry.
     * 阻止编辑器正常完成。
     * 当编辑器(Editor)处于io操作的error的时候，或者editor正在被调用的时时目标条目被驱逐 这种阻止是必须的
     * 我们需要删除编辑器创建的文件，并防止创建新的文件。如果编辑器被分离，其他的编辑器可以编辑这个Entry
     */
    void detach() {
      if (entry.currentEditor == this) {
        for (int i = 0; i < valueCount; i++) {
          try {
            fileSystem.delete(entry.dirtyFiles[i]);
          } catch (IOException e) {
            // This file is potentially leaked. Not much we can do about that.
          }
        }
        entry.currentEditor = null;
      }
    }

    /**
     * 获取cleanFile的输入流 在commit的时候把done设为true
     * Returns an unbuffered input stream to read the last committed value, or null if no value has
     * been committed.
     */
    public Source newSource(int index) {
      synchronized (DiskLruCache.this) {
        if (done) {
          throw new IllegalStateException();
        }
        // 如果entry不可读，并且已经有编辑器了(其实就是dirty)
        if (!entry.readable || entry.currentEditor != this) {
          return null;
        }
        try {
          return fileSystem.source(entry.cleanFiles[index]);
        } catch (FileNotFoundException e) {
          return null;
        }
      }
    }

    /**
     * 获取dirty文件的输出流，如果在写入数据的时候出现错误，会立即停止。返回的输出流不会抛IO异常
     * Returns a new unbuffered output stream to write the value at {@code index}. If the underlying
     * output stream encounters errors when writing to the filesystem, this edit will be aborted
     * when {@link #commit} is called. The returned output stream does not throw IOExceptions.
     */
    public Sink newSink(int index) {
      synchronized (DiskLruCache.this) {
        if (done) {
          throw new IllegalStateException();
        }
        // 如果编辑器是不自己的，不能操作
        if (entry.currentEditor != this) {
          return Okio.blackhole();
        }

        // 如果entry不可读，把对应的written设为true
        if (!entry.readable) {
          written[index] = true;
        }

        File dirtyFile = entry.dirtyFiles[index];
        Sink sink;
        try {
          sink = fileSystem.sink(dirtyFile);
        } catch (FileNotFoundException e) {
          return Okio.blackhole();
        }
        return new FaultHidingSink(sink) {
          @Override protected void onException(IOException e) {
            synchronized (DiskLruCache.this) {
              detach();
            }
          }
        };
      }
    }

    /**
     * 把dirtyFiles里面的内容移动到cleanFiles里，这样才能够让别的editor访问到
     * Commits this edit so it is visible to readers.  This releases the edit lock so another edit
     * may be started on the same key.
     */
    public void commit() throws IOException {
      synchronized (DiskLruCache.this) {
        if (done) {
          throw new IllegalStateException();
        }
        if (entry.currentEditor == this) {
          completeEdit(this, true);
        }
        done = true;
      }
    }

    /**
     * 终止这个edit，这将释放锁来让另外也给eidt开始
     * Aborts this edit. This releases the edit lock so another edit may be started on the same
     * key.
     */
    public void abort() throws IOException {
      synchronized (DiskLruCache.this) {
        if (done) {
          throw new IllegalStateException();
        }
        if (entry.currentEditor == this) {
          completeEdit(this, false);
        }
        done = true;
      }
    }

    /**
     * 除非正在编辑，否则终止
     */
    public void abortUnlessCommitted() {
      synchronized (DiskLruCache.this) {
        if (!done && entry.currentEditor == this) {
          try {
            completeEdit(this, false);
          } catch (IOException ignored) {
          }
        }
      }
    }
  }

  private final class Entry {
    final String key;

    /** Lengths of this entry's files. */
    final long[] lengths;
    final File[] cleanFiles;
    final File[] dirtyFiles;

    /** True if this entry has ever been published. */
    boolean readable; // 实体是否可读，可读为true，不可读为false

    /** The ongoing edit or null if this entry is not being edited. */
    Editor currentEditor; // 编辑器，如果实体没有被编辑过，则为null

    /** The sequence number of the most recently committed edit to this entry. */
    long sequenceNumber; // 最近提交的Entry的序列

    Entry(String key) {
      this.key = key;
      // valueCount在构造DiskLruCache时传入的参数默认大小为2
      lengths = new long[valueCount];
      cleanFiles = new File[valueCount];
      dirtyFiles = new File[valueCount];

      // The names are repetitive so re-use the same builder to avoid allocations.
      StringBuilder fileBuilder = new StringBuilder(key).append('.');
      int truncateTo = fileBuilder.length();
      for (int i = 0; i < valueCount; i++) {
        fileBuilder.append(i);
        cleanFiles[i] = new File(directory, fileBuilder.toString());// key.1       key.2
        fileBuilder.append(".tmp");
        dirtyFiles[i] = new File(directory, fileBuilder.toString());// key.1.tmp   key.2.tmp
        fileBuilder.setLength(truncateTo);
      }
    }

    /** Set lengths using decimal numbers like "10123". */
    void setLengths(String[] strings) throws IOException {
      if (strings.length != valueCount) {
        throw invalidLengths(strings);
      }

      try {
        for (int i = 0; i < strings.length; i++) {
          lengths[i] = Long.parseLong(strings[i]);
        }
      } catch (NumberFormatException e) {
        throw invalidLengths(strings);
      }
    }

    /** Append space-prefixed lengths to {@code writer}. */
    void writeLengths(BufferedSink writer) throws IOException {
      for (long length : lengths) {
        writer.writeByte(' ').writeDecimalLong(length);
      }
    }

    private IOException invalidLengths(String[] strings) throws IOException {
      throw new IOException("unexpected journal line: " + Arrays.toString(strings));
    }

    /**
     * 就是为每个 cleanFiles 创建source  并new一个Snapshot返回
     * Returns a snapshot of this entry. This opens all streams eagerly to guarantee that we see a
     * single published snapshot. If we opened streams lazily then the streams could come from
     * different edits.
     */
    Snapshot snapshot() {
      // 首先判断 线程是否有DiskLruCache对象的锁
      if (!Thread.holdsLock(DiskLruCache.this)) throw new AssertionError();
      // new了一个Souce类型数组，容量为2
      Source[] sources = new Source[valueCount];
      // clone一个long类型的数组，容量为2
      long[] lengths = this.lengths.clone(); // Defensive copy since these can be zeroed out.
      // 获取cleanFile的Source，用于读取cleanFile中的数据，
      // 并用得到的souce、Entry.key、Entry.length、sequenceNumber数据构造一个Snapshot对象
      try {
        for (int i = 0; i < valueCount; i++) {
          sources[i] = fileSystem.source(cleanFiles[i]);
        }
        return new Snapshot(key, sequenceNumber, sources, lengths);
      } catch (FileNotFoundException e) {
        // A file must have been deleted manually!
        for (int i = 0; i < valueCount; i++) {
          if (sources[i] != null) {
            Util.closeQuietly(sources[i]);
          } else {
            break;
          }
        }
        // Since the entry is no longer valid, remove it so the metadata is accurate (i.e. the cache
        // size.)
        // 由于条目不再有效，请将其删除，以使元数据准确无误
        try {
          removeEntry(this);
        } catch (IOException ignored) {
        }
        return null;
      }
    }
  }
}
