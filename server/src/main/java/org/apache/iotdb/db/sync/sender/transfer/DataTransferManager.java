/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements.  See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership.  The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with the License.  You may obtain
 * a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied.  See the License for the specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.db.sync.sender.transfer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileLock;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.FileUtils;
import org.apache.iotdb.db.concurrent.IoTDBThreadPoolFactory;
import org.apache.iotdb.db.concurrent.ThreadName;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.engine.storagegroup.TsFileResource;
import org.apache.iotdb.db.exception.SyncConnectionException;
import org.apache.iotdb.db.metadata.MetadataConstant;
import org.apache.iotdb.db.sync.sender.conf.Constans;
import org.apache.iotdb.db.sync.sender.conf.SyncSenderConfig;
import org.apache.iotdb.db.sync.sender.conf.SyncSenderDescriptor;
import org.apache.iotdb.db.sync.sender.manage.SyncFileManager;
import org.apache.iotdb.db.sync.sender.recover.SyncSenderLogAnalyzer;
import org.apache.iotdb.db.sync.sender.recover.SyncSenderLogger;
import org.apache.iotdb.db.utils.SyncUtils;
import org.apache.iotdb.service.sync.thrift.ResultStatus;
import org.apache.iotdb.service.sync.thrift.SyncService;
import org.apache.iotdb.tsfile.utils.BytesUtils;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SyncSenderImpl is used to transfer tsfiles that needs to sync to receiver.
 */
public class DataTransferManager implements IDataTransferManager {

  private static final Logger logger = LoggerFactory.getLogger(DataTransferManager.class);

  private static SyncSenderConfig config = SyncSenderDescriptor.getInstance().getConfig();

  private static final int BATCH_LINE = 1000;

  /**
   * When transferring schema information, it is a better choice to transfer only new schema
   * information, avoiding duplicate data transmission. The schema log is self-increasing, so the
   * location is recorded once after each synchronization task for the next synchronization task to
   * use.
   */
  private int schemaFileLinePos;

  private TTransport transport;

  private SyncService.Client serviceClient;

  private Set<String> allSG;

  private Map<String, Set<File>> toBeSyncedFilesMap;

  private Map<String, Set<File>> deletedFilesMap;

  private Map<String, Set<File>> lastLocalFilesMap;

  /**
   * If true, sync is in execution.
   **/
  private volatile boolean syncStatus = false;

  /**
   * Record sync progress in log.
   */
  private SyncSenderLogger syncLog;

  private SyncFileManager syncFileManager = SyncFileManager.getInstance();

  private ScheduledExecutorService executorService;

  private DataTransferManager() {
    init();
  }

  public static DataTransferManager getInstance() {
    return InstanceHolder.INSTANCE;
  }

  /**
   * Create a sender and sync files to the receiver periodically.
   */
  public static void main(String[] args) throws IOException {
    Thread.currentThread().setName(ThreadName.SYNC_CLIENT.getName());
    DataTransferManager fileSenderImpl = new DataTransferManager();
    fileSenderImpl.verifySingleton();
    fileSenderImpl.startMonitor();
    fileSenderImpl.startTimedTask();
  }

  /**
   * Verify whether the client lock file is locked or not, ensuring that only one client is
   * running.
   */
  private void verifySingleton() throws IOException {
    File lockFile = new File(config.getLockFilePath());
    if (!lockFile.getParentFile().exists()) {
      lockFile.getParentFile().mkdirs();
    }
    if (!lockFile.exists()) {
      lockFile.createNewFile();
    }
    if (!lockInstance(config.getLockFilePath())) {
      logger.error("Sync client is already running.");
      System.exit(1);
    }
  }

  /**
   * Try to lock lockfile. if failed, it means that sync client has benn started.
   *
   * @param lockFile path of lock file
   */
  private boolean lockInstance(final String lockFile) {
    try {
      final File file = new File(lockFile);
      final RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw");
      final FileLock fileLock = randomAccessFile.getChannel().tryLock();
      if (fileLock != null) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
          try {
            fileLock.release();
            randomAccessFile.close();
          } catch (Exception e) {
            logger.error("Unable to remove lock file: {}", lockFile, e);
          }
        }));
        return true;
      }
    } catch (Exception e) {
      logger.error("Unable to create and/or lock file: {}", lockFile, e);
    }
    return false;
  }


  @Override
  public void init() {
    if (executorService == null) {
      executorService = IoTDBThreadPoolFactory.newScheduledThreadPool(2,
          "sync-client-timer");
    }
  }

  /**
   * Start monitor thread, which monitor sync status.
   */
  private void startMonitor() {
    executorService.scheduleWithFixedDelay(() -> {
      if (syncStatus) {
        logger.info("Sync process for receiver {} is in execution!", config.getSyncReceiverName());
      }
    }, Constans.SYNC_MONITOR_DELAY, Constans.SYNC_MONITOR_PERIOD, TimeUnit.SECONDS);
  }

  /**
   * Start sync task in a certain time.
   */
  private void startTimedTask() {
    executorService.scheduleWithFixedDelay(() -> {
      try {
        syncAll();
      } catch (SyncConnectionException | IOException | TException e) {
        logger.error("Sync failed", e);
        stop();
      }
    }, Constans.SYNC_PROCESS_DELAY, Constans.SYNC_PROCESS_PERIOD, TimeUnit.SECONDS);
  }

  @Override
  public void stop() {
    executorService.shutdownNow();
    executorService = null;
  }

  @Override
  public void syncAll() throws SyncConnectionException, IOException, TException {

    // 1. Connect to sync receiver and confirm identity
    establishConnection(config.getServerIp(), config.getServerPort());
    confirmIdentity();
    serviceClient.startSync();

    // 2. Sync Schema
    syncSchema();

    // 3. Sync all data
    String[] dataDirs = IoTDBDescriptor.getInstance().getConfig().getDataDirs();
    for (String dataDir : dataDirs) {
      logger.info("Start to sync data in data dir {}", dataDir);

      config.update(dataDir);
      syncFileManager.getValidFiles(dataDir);
      allSG = syncFileManager.getAllSG();
      lastLocalFilesMap = syncFileManager.getLastLocalFilesMap();
      deletedFilesMap = syncFileManager.getDeletedFilesMap();
      toBeSyncedFilesMap = syncFileManager.getToBeSyncedFilesMap();
      checkRecovery();
      if (SyncUtils.isEmpty(deletedFilesMap) && SyncUtils.isEmpty(toBeSyncedFilesMap)) {
        logger.info("There has no data to sync in data dir {}", dataDir);
        continue;
      }
      sync();
      endSync();
      logger.info("Finish to sync data in data dir {}", dataDir);
    }

    // 4. notify receiver that synchronization finish
    // At this point the synchronization has finished even if connection fails
    try {
      serviceClient.endSync();
      transport.close();
      logger.info("Sync process has finished.");
    } catch (TException e) {
      logger.error("Unable to connect to receiver.", e);
    }
  }

  private void checkRecovery() {
    new SyncSenderLogAnalyzer(config.getSenderFolderPath()).recover();
  }

  @Override
  public void establishConnection(String serverIp, int serverPort) throws SyncConnectionException {
    transport = new TSocket(serverIp, serverPort);
    TProtocol protocol = new TBinaryProtocol(transport);
    serviceClient = new SyncService.Client(protocol);
    try {
      transport.open();
    } catch (TTransportException e) {
      logger.error("Cannot connect to the receiver.");
      throw new SyncConnectionException(e);
    }
  }

  @Override
  public void confirmIdentity() throws SyncConnectionException {
    try {
      ResultStatus status = serviceClient.check(InetAddress.getLocalHost().getHostAddress(),
          getOrCreateUUID(config.getUuidPath()));
      if (!status.success) {
        throw new SyncConnectionException(
            "The receiver rejected the synchronization task because " + status.errorMsg);
      }
    } catch (Exception e) {
      logger.error("Cannot confirm identity with the receiver.");
      throw new SyncConnectionException(e);
    }
  }

  /**
   * UUID marks the identity of sender for receiver.
   */
  public String getOrCreateUUID(String uuidPath) throws IOException {
    File file = new File(uuidPath);
    String uuid;
    if (!file.getParentFile().exists()) {
      file.getParentFile().mkdirs();
    }
    if (!file.exists()) {
      try (FileOutputStream out = new FileOutputStream(file)) {
        file.createNewFile();
        uuid = generateUUID();
        out.write(uuid.getBytes());
      } catch (IOException e) {
        logger.error("Cannot insert UUID to file {}", file.getPath());
        throw new IOException(e);
      }
    } else {
      try (BufferedReader bf = new BufferedReader((new FileReader(uuidPath)))) {
        uuid = bf.readLine();
      } catch (IOException e) {
        logger.error("Cannot read UUID from file{}", file.getPath());
        throw new IOException(e);
      }
    }
    return uuid;
  }

  private String generateUUID() {
    return UUID.randomUUID().toString().replaceAll("-", "");
  }

  @Override
  public void syncSchema() throws SyncConnectionException, TException {
    int retryCount = 0;
    serviceClient.initSyncData(MetadataConstant.METADATA_LOG);
    while (true) {
      if (retryCount > Constans.MAX_SYNC_FILE_TRY) {
        throw new SyncConnectionException(String
            .format("Can not sync schema after %s retries.", Constans.MAX_SYNC_FILE_TRY));
      }
      try {
        if (tryToSyncSchema()) {
          writeSyncSchemaPos(getSchemaPosFile());
          break;
        }
      } finally {
        retryCount++;
      }
    }
  }

  private boolean tryToSyncSchema() {
    int schemaPos = readSyncSchemaPos(getSchemaPosFile());

    // start to sync file data and get md5 of this file.
    try (BufferedReader br = new BufferedReader(new FileReader(getSchemaLogFile()));
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Constans.DATA_CHUNK_SIZE)) {
      schemaFileLinePos = 0;
      while (schemaFileLinePos++ <= schemaPos) {
        br.readLine();
      }
      MessageDigest md = MessageDigest.getInstance(Constans.MESSAGE_DIGIT_NAME);
      String line;
      int cntLine = 0;
      while ((line = br.readLine()) != null) {
        schemaFileLinePos++;
        byte[] singleLineData = BytesUtils.stringToBytes(line);
        bos.write(singleLineData);
        md.update(singleLineData);
        if (cntLine++ == BATCH_LINE) {
          ByteBuffer buffToSend = ByteBuffer.wrap(bos.toByteArray());
          bos.reset();
          ResultStatus status = serviceClient.syncData(buffToSend);
          if (!status.success) {
            logger.error("Receiver failed to receive metadata because {}, retry.", status.errorMsg);
            return false;
          }
          cntLine = 0;
        }
      }
      if (bos.size() != 0) {
        ByteBuffer buffToSend = ByteBuffer.wrap(bos.toByteArray());
        bos.reset();
        ResultStatus status = serviceClient.syncData(buffToSend);
        if (!status.success) {
          logger.error("Receiver failed to receive metadata because {}, retry.", status.errorMsg);
          return false;
        }
      }

      // check md5
      return checkMD5ForSchema((new BigInteger(1, md.digest())).toString(16));
    } catch (NoSuchAlgorithmException | IOException | TException e) {
      logger.error("Can not finish transfer schema to receiver", e);
      return false;
    }
  }

  /**
   * Check MD5 of schema to make sure that the receiver receives the schema correctly
   */
  private boolean checkMD5ForSchema(String md5OfSender) throws TException {
    ResultStatus status = serviceClient.checkDataMD5(md5OfSender);
    if (status.success && md5OfSender.equals(status.msg)) {
      logger.info("Receiver has received schema successfully, retry.");
      return true;
    } else {
      logger
          .error("MD5 check of schema file {} failed, retry", getSchemaLogFile().getAbsoluteFile());
      return false;
    }
  }

  private int readSyncSchemaPos(File syncSchemaLogFile) {
    try {
      if (syncSchemaLogFile.exists()) {
        try (BufferedReader br = new BufferedReader(new FileReader(syncSchemaLogFile))) {
          return Integer.parseInt(br.readLine());
        }
      }
    } catch (IOException e) {
      logger.error("Can not find file {}", syncSchemaLogFile.getAbsoluteFile(), e);
    }
    return 0;
  }

  private void writeSyncSchemaPos(File syncSchemaLogFile) {
    try {
      if (!syncSchemaLogFile.exists()) {
        syncSchemaLogFile.createNewFile();
      }
      try (BufferedWriter br = new BufferedWriter(new FileWriter(syncSchemaLogFile))) {
        br.write(Integer.toString(schemaFileLinePos));
      }
    } catch (IOException e) {
      logger.error("Can not find file {}", syncSchemaLogFile.getAbsoluteFile(), e);
    }
  }

  @Override
  public void sync() throws IOException {
    try {
      syncStatus = true;
      syncLog = new SyncSenderLogger(getSchemaLogFile());

      for (String sgName : allSG) {
        lastLocalFilesMap.putIfAbsent(sgName, new HashSet<>());
        syncLog = new SyncSenderLogger(getSyncLogFile());
        try {
          ResultStatus status = serviceClient.init(sgName);
          if (!status.success) {
            throw new SyncConnectionException("Unable init receiver because " + status.errorMsg);
          }
        } catch (TException | SyncConnectionException e) {
          throw new SyncConnectionException("Unable to connect to receiver", e);
        }
        logger.info("Sync process starts to transfer data of storage group {}", sgName);
        syncDeletedFilesNameInOneGroup(sgName,
            deletedFilesMap.getOrDefault(sgName, new HashSet<>()));
        syncDataFilesInOneGroup(sgName, toBeSyncedFilesMap.getOrDefault(sgName, new HashSet<>()));
      }

    } catch (SyncConnectionException e) {
      logger.error("cannot finish sync process", e);
    } finally {
      if (syncLog != null) {
        syncLog.close();
      }
      syncStatus = false;
    }
  }

  @Override
  public void syncDeletedFilesNameInOneGroup(String sgName, Set<File> deletedFilesName)
      throws IOException {
    if (deletedFilesName.isEmpty()) {
      logger.info("There has no deleted files to be synced in storage group {}", sgName);
      return;
    }
    syncLog.startSyncDeletedFilesName();
    logger.info("Start to sync names of deleted files in storage group {}", sgName);
    for (File file : deletedFilesName) {
      try {
        if (serviceClient.syncDeletedFileName(file.getName()).success) {
          lastLocalFilesMap.get(sgName).add(file);
          syncLog.finishSyncDeletedFileName(file);
        }
      } catch (TException e) {
        logger.error("Can not sync deleted file name {}, skip it.", file);
      }
    }
    logger.info("Finish to sync names of deleted files in storage group {}", sgName);
  }

  @Override
  public void syncDataFilesInOneGroup(String sgName, Set<File> toBeSyncFiles)
      throws SyncConnectionException, IOException {
    if (toBeSyncFiles.isEmpty()) {
      logger.info("There has no new tsfiles to be synced in storage group {}", sgName);
      return;
    }
    syncLog.startSyncTsFiles();
    logger.info("Sync process starts to transfer data of storage group {}", sgName);
    int cnt = 0;
    for (File tsfile : toBeSyncFiles) {
      cnt++;
      File snapshotFile = null;
      try {
        snapshotFile = makeFileSnapshot(tsfile);
        // firstly sync .restore file, then sync tsfile
        syncSingleFile(new File(snapshotFile, TsFileResource.RESOURCE_SUFFIX));
        syncSingleFile(snapshotFile);
        lastLocalFilesMap.get(sgName).add(tsfile);
        syncLog.finishSyncTsfile(tsfile);
        logger.info("Task of synchronization has completed {}/{}.", cnt, toBeSyncFiles.size());
      } catch (IOException e) {
        logger.info(
            "Tsfile {} can not make snapshot, so skip the tsfile and continue to sync other tsfiles",
            tsfile, e);
      } finally {
        if (snapshotFile != null) {
          snapshotFile.delete();
        }
      }
    }
    logger.info("Sync process has finished storage group {}.", sgName);
  }

  /**
   * Make snapshot<hard link> for new tsfile and its .restore file.
   *
   * @param file new tsfile to be synced
   */
  private File makeFileSnapshot(File file) throws IOException {
    File snapshotFile = SyncUtils.getSnapshotFile(file);
    if (!snapshotFile.getParentFile().exists()) {
      snapshotFile.getParentFile().mkdirs();
    }
    Path link = FileSystems.getDefault().getPath(snapshotFile.getAbsolutePath());
    Path target = FileSystems.getDefault().getPath(snapshotFile.getAbsolutePath());
    Files.createLink(link, target);
    link = FileSystems.getDefault()
        .getPath(snapshotFile.getAbsolutePath() + TsFileResource.RESOURCE_SUFFIX);
    target = FileSystems.getDefault()
        .getPath(snapshotFile.getAbsolutePath() + TsFileResource.RESOURCE_SUFFIX);
    Files.createLink(link, target);
    return snapshotFile;
  }

  /**
   * Transfer data of a tsfile to the receiver.
   */
  private void syncSingleFile(File snapshotFile) throws SyncConnectionException {
    try {
      int retryCount = 0;
      MessageDigest md = MessageDigest.getInstance(Constans.MESSAGE_DIGIT_NAME);
      serviceClient.initSyncData(snapshotFile.getName());
      outer:
      while (true) {
        retryCount++;
        if (retryCount > Constans.MAX_SYNC_FILE_TRY) {
          throw new SyncConnectionException(String
              .format("Can not sync file %s after %s tries.", snapshotFile.getAbsoluteFile(),
                  Constans.MAX_SYNC_FILE_TRY));
        }
        md.reset();
        byte[] buffer = new byte[Constans.DATA_CHUNK_SIZE];
        int dataLength;
        try (FileInputStream fis = new FileInputStream(snapshotFile);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Constans.DATA_CHUNK_SIZE)) {
          while ((dataLength = fis.read(buffer)) != -1) { // cut the file into pieces to send
            bos.write(buffer, 0, dataLength);
            md.update(buffer, 0, dataLength);
            ByteBuffer buffToSend = ByteBuffer.wrap(bos.toByteArray());
            bos.reset();
            ResultStatus status = serviceClient.syncData(buffToSend);
            if (!status.success) {
              logger.info("Receiver failed to receive data from {} because {}, retry.",
                  status.errorMsg, snapshotFile.getAbsoluteFile());
              continue outer;
            }
          }
        }

        // the file is sent successfully
        String md5OfSender = (new BigInteger(1, md.digest())).toString(16);
        ResultStatus status = serviceClient.checkDataMD5(md5OfSender);
        if (status.success && md5OfSender.equals(status.msg)) {
          logger.info("Receiver has received {} successfully.", snapshotFile.getAbsoluteFile());
          break;
        } else {
          logger.error("MD5 check of tsfile {} failed, retry", snapshotFile.getAbsoluteFile());
        }
      }
    } catch (IOException | TException | NoSuchAlgorithmException e) {
      throw new SyncConnectionException("Cannot sync data with receiver.", e);
    }
  }

  private void endSync() {
    File currentLocalFile = getCurrentLogFile();
    File lastLocalFile = new File(config.getLastFileInfo());

    // 1. Write file list to currentLocalFile
    try (BufferedWriter bw = new BufferedWriter(new FileWriter(currentLocalFile))) {
      for (Set<File> currentLocalFiles : lastLocalFilesMap.values()) {
        for (File file : currentLocalFiles) {
          bw.write(file.getAbsolutePath());
          bw.newLine();
        }
        bw.flush();
      }
    } catch (IOException e) {
      logger.error("Can not clear sync log {}", lastLocalFile.getAbsoluteFile(), e);
    }

    // 2. Rename currentLocalFile to lastLocalFile
    lastLocalFile.delete();
    currentLocalFile.renameTo(lastLocalFile);

    // 3. delete snapshot directory
    try {
      FileUtils.deleteDirectory(new File(config.getSnapshotPath()));
    } catch (IOException e) {
      logger.error("Can not clear snapshot directory {}", config.getSnapshotPath(), e);
    }

    // 4. delete sync log file
    getSyncLogFile().delete();
  }


  private File getSchemaPosFile() {
    return new File(config.getSenderFolderPath(), Constans.SCHEMA_POS_FILE_NAME);
  }

  private File getSchemaLogFile() {
    return new File(IoTDBDescriptor.getInstance().getConfig().getSchemaDir(),
        MetadataConstant.METADATA_LOG);
  }

  private static class InstanceHolder {

    private static final DataTransferManager INSTANCE = new DataTransferManager();
  }

  private File getSyncLogFile() {
    return new File(config.getSenderFolderPath(), Constans.SYNC_LOG_NAME);
  }

  private File getCurrentLogFile() {
    return new File(config.getSenderFolderPath(), Constans.CURRENT_LOCAL_FILE_NAME);
  }

  public void setConfig(SyncSenderConfig config) {
    DataTransferManager.config = config;
  }
}