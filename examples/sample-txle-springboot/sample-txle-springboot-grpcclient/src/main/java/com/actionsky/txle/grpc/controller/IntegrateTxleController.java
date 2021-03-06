/*
 * Copyright (c) 2018-2020 ActionTech.
 * License: http://www.apache.org/licenses/LICENSE-2.0 Apache License 2.0 or higher.
 */

package com.actionsky.txle.grpc.controller;

import com.actionsky.txle.grpc.*;
import com.actionsky.txle.grpc.service.PrimaryCustomService;
import com.actionsky.txle.grpc.service.SecondaryCustomService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Gannalyo
 * @since 2020/2/11
 */
@RestController
public class IntegrateTxleController {
    private static final Logger LOG = LoggerFactory.getLogger(IntegrateTxleController.class);

    private TxleTransactionServiceGrpc.TxleTransactionServiceStub stubService;
    private TxleTransactionServiceGrpc.TxleTransactionServiceBlockingStub stubBlockingService;
    private TxleGrpcServerStreamObserver serverStreamObserver;
    private StreamObserver<TxleGrpcClientStream> clientStreamObserver;
    private String primaryDBSchema;
    private String secondaryDBSchema;
    private Map<String, String> dbMD5InfoMap = new HashMap<>();
    private int globalTxIndex = 0;

    @Value("${alpha.cluster.address:127.0.0.1:8080}")
    private String txleGrpcServerAddress;

    @Autowired
    private PrimaryCustomService primaryCustomService;

    @Autowired
    private SecondaryCustomService secondaryCustomService;

    @Autowired
    private RestTemplate restTemplate;

    @PostConstruct
    void init() {
        ManagedChannel channel = ManagedChannelBuilder.forTarget(txleGrpcServerAddress).usePlaintext()/*.maxInboundMessageSize(10 * 1024 * 1024)*/.build();
        this.stubService = TxleTransactionServiceGrpc.newStub(channel);
        this.stubBlockingService = TxleTransactionServiceGrpc.newBlockingStub(channel);
        this.serverStreamObserver = new TxleGrpcServerStreamObserver(this);
        // 正式环境请设置正确的服务名称、IP和类别
        TxleClientConfig clientConfig = TxleClientConfig.newBuilder().setServiceName("actiontech-dble").setServiceIP("0.0.0.0").setServiceCategory("").build();
        stubService.onInitialize(clientConfig, new TxleServerConfigStreamObserver(this));

        this.onInitialize();
        this.initDBSchema();
        new Thread(() -> this.onSynDatabaseFullDose()).start();
    }

    private void onInitialize() {
        this.clientStreamObserver = stubService.onBuildBidirectionalStream(this.serverStreamObserver);
        serverStreamObserver.setClientStreamObserver(clientStreamObserver);
    }

    private void initDBSchema() {
        List list = this.primaryCustomService.executeQuery("select database()");
        if (list != null && !list.isEmpty()) {
            this.primaryDBSchema = list.get(0).toString();
        }
        list = this.secondaryCustomService.executeQuery("select database()");
        if (list != null && !list.isEmpty()) {
            this.secondaryDBSchema = list.get(0).toString();
        }
    }

    public String getTxleGrpcServerAddress() {
        return txleGrpcServerAddress;
    }

    public PrimaryCustomService getPrimaryCustomService() {
        return this.primaryCustomService;
    }

    public SecondaryCustomService getSecondaryCustomService() {
        return this.secondaryCustomService;
    }

    public String getPrimaryDBSchema() {
        return this.primaryDBSchema;
    }

    public String getSecondaryDBSchema() {
        return this.secondaryDBSchema;
    }

    public void onReconnect() {
        this.onInitialize();
    }

    @GetMapping("/startTransaction/{scene}")
    public void onStartTransaction(@PathVariable int scene) {
        onStartTransaction(scene, "txle-global-transaction-id-00" + globalTxIndex++);
    }

    // 全局事务下的子事务可能分批次启动，非第一批次的需带上第一批次的全局事务标识
    @PostMapping("/startTransaction/{scene}/{globalTxId}")
    public void onStartTransaction(@PathVariable int scene, @PathVariable String globalTxId) {
        TxleTransactionStart.Builder transaction = TxleTransactionStart.newBuilder().setServiceName("actiontech-dble").setServiceIP("0.0.0.0").setGlobalTxId(globalTxId).setTimeout(0);
        boolean isStartedTx = false;

        // 场景：1-正常，2-异常，3-重试且成功，4-重试不成功，5-多批次子事务(执行1后再执行5)，6-超时，7-一个子事务内两条一样的操作，8-无限重试和超时，9-暂停
        switch (scene) {
            case 1:
                normalSubTx(transaction);
                break;
            case 2:
                abnormalSubTx(transaction);
                break;
            case 3:
                // 该场景测试说明：在测试前需破坏网络或数据库，使数据库secondaryDBSchema的sql“update txle_sample_merchant set balance = balance + 1 where id = 1”执行失败，
                // 在执行结束事件前，恢复网络或数据库，即可重试成功
                retrySuccessfulSubTx(transaction);
                break;
            case 4:
                retryFailedSubTx(transaction);
                break;
            case 5:
                normalSubTxForFirstTime(transaction);
                break;
            case 55:
                normalSubTxForSecondTime(transaction);
                break;
            case 6:
                timeoutAfterEndingAllSubTx(transaction);
                break;
            case 61:
                timeoutBeforeEndingAllSubTx001(transaction);
                break;
            case 62:
                timeoutBeforeEndingAllSubTx002(transaction);
                break;
            case 63:
                timeoutBeforeEndingAllSubTx003(transaction);
                break;
            case 7:
                twoSameOperationSubTx(transaction);
                break;
            case 8:
                retryFailedAndTimeoutSubTx(transaction);
                break;
            case 9:
                normalSubTx(transaction);
                break;
            default:
                return;
        }

        try {
            TxleTxStartAck startAck = stubBlockingService.onStartTransaction(transaction.build());
            if (TxleTxEndAck.TransactionStatus.ABORTED.ordinal() == startAck.getStatus().ordinal()) {
                throw new RuntimeException("Occur an exception when starting global transaction [" + transaction.getGlobalTxId() + "].");
            } else {
                isStartedTx = true;
                LOG.info("Successfully started global transaction [" + globalTxId + "].");
                startAck.getSubTxSqlList().forEach(subSql -> {
                    if (primaryDBSchema.equals(subSql.getDbSchema())) {
                        primaryCustomService.executeSubTxSqls(subSql.getSubTxSqlList());
                    } else if (secondaryDBSchema.equals(subSql.getDbSchema())) {
                        secondaryCustomService.executeSubTxSqls(subSql.getSubTxSqlList());
                    }
                });
            }
        } catch (Exception e) {
            LOG.error("Occur an exception when starting global transaction [{}].", transaction.getGlobalTxId(), e);
        } finally {
            try {
                if (scene == 6 || scene == 62) {
                    // 模拟超时场景
                    Thread.sleep(3000);
                } else if (scene == 9) {
                    // 暂停20秒，此时需尽快触发暂停操作
                    Thread.sleep(20000);
                }
            } catch (InterruptedException e) {
            }
            // 只有事务开启成功，才执行事务结束，不用担心不会结束全局事务，因为在服务端若开启事务失败，会自动结束全局事务
            if (isStartedTx) {
                this.onEndTransaction(scene, transaction.getGlobalTxId());
            }
        }
    }

    private void normalSubTx(TxleTransactionStart.Builder transaction) {
        int currentGlobalTxIdIndex = Integer.parseInt(transaction.getGlobalTxId().substring(29));

//        Map<Integer, String> sqlMap = new HashMap<>();
//        sqlMap.put(1, "insert into txle_sample_user (name, balance) values ('xiongjiujiu', 1000000)");
//        sqlMap.put(2, "update txle_sample_user set balance = balance - 1 where id = 2");
//        sqlMap.put(3, "delete from txle_sample_user where id = 2");
        List<TxleSubTransactionStart> subTxList = new ArrayList<>();

        TxleSubTransactionStart subTx1 = TxleSubTransactionStart.newBuilder().setSql("update txle_sample_user set balance = balance - 1 where id = 1")
                .setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-001").setDbNodeId("10.186.62.75").setDbSchema(primaryDBSchema).setTimeout(0).setRetries(0).setOrder(1).build();
        // 第二个SQL故意编写异常
        TxleSubTransactionStart subTx2 = TxleSubTransactionStart.newBuilder().setSql("update txle_sample_merchant set balance = balance + 1 where id = 1")
                .setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-002").setDbNodeId("10.186.62.75").setDbSchema(secondaryDBSchema).setTimeout(0).setRetries(0).setOrder(2).build();

        subTxList.add(subTx1);
        subTxList.add(subTx2);

        transaction.addAllSubTxInfo(subTxList);
    }

    private void abnormalSubTx(TxleTransactionStart.Builder transaction) {
        int currentGlobalTxIdIndex = Integer.parseInt(transaction.getGlobalTxId().substring(29));

        List<TxleSubTransactionStart> subTxList = new ArrayList<>();

        TxleSubTransactionStart subTx1 = TxleSubTransactionStart.newBuilder().setSql("update txle_sample_user set balance = balance - 1 where id = 1")
                .setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-001").setDbNodeId("10.186.62.75").setDbSchema(primaryDBSchema).setTimeout(0).setRetries(0).setOrder(1).build();
        // 第二个SQL故意编写异常
        TxleSubTransactionStart subTx2 = TxleSubTransactionStart.newBuilder().setSql("update txle_sample_merchant set balance = balance + 1 where iddddddd = 1")
                .setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-002").setDbNodeId("10.186.62.75").setDbSchema(secondaryDBSchema).setTimeout(0).setRetries(0).setOrder(2).build();

        subTxList.add(subTx1);
        subTxList.add(subTx2);

        transaction.addAllSubTxInfo(subTxList);
    }

    private void retrySuccessfulSubTx(TxleTransactionStart.Builder transaction) {
        int currentGlobalTxIdIndex = Integer.parseInt(transaction.getGlobalTxId().substring(29));

        List<TxleSubTransactionStart> subTxList = new ArrayList<>();

        TxleSubTransactionStart subTx1 = TxleSubTransactionStart.newBuilder().setSql("update txle_sample_user set balance = balance - 1 where id = 1")
                .setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-001").setDbNodeId("10.186.62.75").setDbSchema(primaryDBSchema).setTimeout(0).setRetries(3).setOrder(1).build();
        // 第二个SQL故意编写异常
        TxleSubTransactionStart subTx2 = TxleSubTransactionStart.newBuilder().setSql("update txle_sample_merchant set balance = balance + 1 where id = 1")
                .setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-002").setDbNodeId("10.186.62.75").setDbSchema(secondaryDBSchema).setTimeout(0).setRetries(3).setOrder(2).build();

        subTxList.add(subTx1);
        subTxList.add(subTx2);

        transaction.addAllSubTxInfo(subTxList);
    }

    private void retryFailedSubTx(TxleTransactionStart.Builder transaction) {
        int currentGlobalTxIdIndex = Integer.parseInt(transaction.getGlobalTxId().substring(29));

        List<TxleSubTransactionStart> subTxList = new ArrayList<>();

        TxleSubTransactionStart subTx1 = TxleSubTransactionStart.newBuilder().setSql("update txle_sample_user set balance = balance - 1 where id = 1")
                .setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-001").setDbNodeId("10.186.62.75").setDbSchema(primaryDBSchema).setTimeout(0).setRetries(3).setOrder(1).build();
        // 第二个SQL故意编写异常
        TxleSubTransactionStart subTx2 = TxleSubTransactionStart.newBuilder().setSql("update txle_sample_merchant set balance = balance + 1 where iddddddd = 1")
                .setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-002").setDbNodeId("10.186.62.75").setDbSchema(secondaryDBSchema).setTimeout(0).setRetries(3).setOrder(2).build();

        subTxList.add(subTx1);
        subTxList.add(subTx2);

        transaction.addAllSubTxInfo(subTxList);
    }

    private void normalSubTxForFirstTime(TxleTransactionStart.Builder transaction) {
        int currentGlobalTxIdIndex = Integer.parseInt(transaction.getGlobalTxId().substring(29));

        List<TxleSubTransactionStart> subTxList = new ArrayList<>();

        TxleSubTransactionStart subTx1 = TxleSubTransactionStart.newBuilder().setSql("update txle_sample_user set balance = balance - 1 where id = 1")
                .setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-001").setDbNodeId("10.186.62.75").setDbSchema(primaryDBSchema).setTimeout(0).setRetries(0).setOrder(3).build();
        TxleSubTransactionStart subTx2 = TxleSubTransactionStart.newBuilder().setSql("update txle_sample_merchant set balance = balance + 1 where id = 1")
                .setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-002").setDbNodeId("10.186.62.75").setDbSchema(secondaryDBSchema).setTimeout(0).setRetries(0).setOrder(4).build();

        subTxList.add(subTx2);
        subTxList.add(subTx1);

        transaction.setTimeout(0).addAllSubTxInfo(subTxList);
        new Thread(() -> restTemplate.postForObject("http://127.0.0.1:8008/startTransaction/{scene}/{globalTxId}", null, String.class, 55, transaction.getGlobalTxId())).start();
    }

    private void normalSubTxForSecondTime(TxleTransactionStart.Builder transaction) {
        int currentGlobalTxIdIndex = Integer.parseInt(transaction.getGlobalTxId().substring(29));

        List<TxleSubTransactionStart> subTxList = new ArrayList<>();

        TxleSubTransactionStart subTx1 = TxleSubTransactionStart.newBuilder().setSql("update txle_sample_user set balance = balance - 1 where id = 1")
                .setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-003").setDbNodeId("10.186.62.75").setDbSchema(primaryDBSchema).setTimeout(0).setRetries(0).setOrder(3).build();
        TxleSubTransactionStart subTx2 = TxleSubTransactionStart.newBuilder().setSql("update txle_sample_merchant set balance = balance + 1 where id = 1")
                .setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-004").setDbNodeId("10.186.62.75").setDbSchema(secondaryDBSchema).setTimeout(0).setRetries(0).setOrder(4).build();

        subTxList.add(subTx2);
        subTxList.add(subTx1);

        transaction.setTimeout(0).addAllSubTxInfo(subTxList);
    }

    private void timeoutAfterEndingAllSubTx(TxleTransactionStart.Builder transaction) {
        int currentGlobalTxIdIndex = Integer.parseInt(transaction.getGlobalTxId().substring(29));

        List<TxleSubTransactionStart> subTxList = new ArrayList<>();

        TxleSubTransactionStart subTx1 = TxleSubTransactionStart.newBuilder().setSql("update txle_sample_user set balance = balance - 1 where id = 1")
                .setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-001").setDbNodeId("10.186.62.75").setDbSchema(primaryDBSchema).setTimeout(0).setRetries(0).setOrder(1).build();
        // 第二个SQL故意编写异常
        TxleSubTransactionStart subTx2 = TxleSubTransactionStart.newBuilder().setSql("update txle_sample_merchant set balance = balance + 1 where id = 1")
                .setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-002").setDbNodeId("10.186.62.75").setDbSchema(secondaryDBSchema).setTimeout(3).setRetries(0).setOrder(2).build();

        subTxList.add(subTx1);
        subTxList.add(subTx2);

        transaction.addAllSubTxInfo(subTxList);
    }

    private void timeoutBeforeEndingAllSubTx001(TxleTransactionStart.Builder transaction) {
        int currentGlobalTxIdIndex = Integer.parseInt(transaction.getGlobalTxId().substring(29));

        List<TxleSubTransactionStart> subTxList = new ArrayList<>();

        TxleSubTransactionStart subTx1 = TxleSubTransactionStart.newBuilder().setSql("update txle_sample_user set balance = balance - 1 where id = 1")
                .setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-001").setDbNodeId("10.186.62.75").setDbSchema(primaryDBSchema).setTimeout(0).setRetries(0).setOrder(1).build();

        subTxList.add(subTx1);

        transaction.addAllSubTxInfo(subTxList);
    }

    private void timeoutBeforeEndingAllSubTx002(TxleTransactionStart.Builder transaction) {
        int currentGlobalTxIdIndex = Integer.parseInt(transaction.getGlobalTxId().substring(29));

        List<TxleSubTransactionStart> subTxList = new ArrayList<>();

        TxleSubTransactionStart subTx2 = TxleSubTransactionStart.newBuilder().setSql("update txle_sample_merchant set balance = balance + 1 where id = 1")
                .setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-002").setDbNodeId("10.186.62.75").setDbSchema(secondaryDBSchema).setTimeout(3).setRetries(0).setOrder(2).build();

        subTxList.add(subTx2);

        transaction.addAllSubTxInfo(subTxList);
    }

    private void timeoutBeforeEndingAllSubTx003(TxleTransactionStart.Builder transaction) {
        int currentGlobalTxIdIndex = Integer.parseInt(transaction.getGlobalTxId().substring(29));

        List<TxleSubTransactionStart> subTxList = new ArrayList<>();

        TxleSubTransactionStart subTx2 = TxleSubTransactionStart.newBuilder().setSql("update txle_sample_merchant set balance = balance + 1 where id = 1")
                .setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-003").setDbNodeId("10.186.62.75").setDbSchema(secondaryDBSchema).setTimeout(0).setRetries(0).setOrder(2).build();

        subTxList.add(subTx2);

        transaction.addAllSubTxInfo(subTxList);
    }

    private void twoSameOperationSubTx(TxleTransactionStart.Builder transaction) {
        int currentGlobalTxIdIndex = Integer.parseInt(transaction.getGlobalTxId().substring(29));

        List<TxleSubTransactionStart> subTxList = new ArrayList<>();

        TxleSubTransactionStart subTx1 = TxleSubTransactionStart.newBuilder().setSql("update txle_sample_user set balance = balance - 1 where id = 1")
                .setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-001").setDbNodeId("10.186.62.75").setDbSchema(primaryDBSchema).setTimeout(0).setRetries(0).setOrder(1).build();
        // 第二个SQL故意编写异常
        TxleSubTransactionStart subTx2 = TxleSubTransactionStart.newBuilder().setSql("update txle_sample_user set balance = balance - 1 where id = 1")
                .setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-002").setDbNodeId("10.186.62.75").setDbSchema(primaryDBSchema).setTimeout(0).setRetries(0).setOrder(2).build();
        TxleSubTransactionStart subTx3 = TxleSubTransactionStart.newBuilder().setSql("update txle_sample_merchant set balance = balance + 1 where id = 1")
                .setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-003").setDbNodeId("10.186.62.75").setDbSchema(secondaryDBSchema).setTimeout(0).setRetries(0).setOrder(3).build();
        TxleSubTransactionStart subTx4 = TxleSubTransactionStart.newBuilder().setSql("update txle_sample_merchant set balance = balance + 1 where id = 1")
                .setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-004").setDbNodeId("10.186.62.75").setDbSchema(secondaryDBSchema).setTimeout(0).setRetries(0).setOrder(4).build();

        subTxList.add(subTx1);
        subTxList.add(subTx2);
        subTxList.add(subTx3);
        subTxList.add(subTx4);

        transaction.addAllSubTxInfo(subTxList);
    }

    private void retryFailedAndTimeoutSubTx(TxleTransactionStart.Builder transaction) {
        int currentGlobalTxIdIndex = Integer.parseInt(transaction.getGlobalTxId().substring(29));

        List<TxleSubTransactionStart> subTxList = new ArrayList<>();

        TxleSubTransactionStart subTx1 = TxleSubTransactionStart.newBuilder().setSql("update txle_sample_user set balance = balance - 1 where id = 1")
                .setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-001").setDbNodeId("10.186.62.75").setDbSchema(primaryDBSchema).setTimeout(0).setRetries(0).setOrder(1).build();
        // 第二个SQL故意编写异常
        TxleSubTransactionStart subTx2 = TxleSubTransactionStart.newBuilder().setSql("update txle_sample_merchant set balance = balance + 1 where iddddddd = 1")
                .setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-002").setDbNodeId("10.186.62.75").setDbSchema(secondaryDBSchema).setTimeout(30).setRetries(-1).setOrder(2).build();

        subTxList.add(subTx1);
        subTxList.add(subTx2);

        transaction.addAllSubTxInfo(subTxList);
    }

    public void onEndTransaction(@PathVariable int scene, @PathVariable String globalTxId) {
        try {
            int currentGlobalTxIdIndex = Integer.parseInt(globalTxId.substring(29));
            TxleTransactionEnd.Builder transaction = TxleTransactionEnd.newBuilder().setIsCanOver(true).setGlobalTxId(globalTxId);

            // 场景：1-正常，2-异常，3-重试且成功，4-重试不成功，5-多批次子事务(执行1后再执行5)，6-超时
            switch (scene) {
                case 1:
                    transaction.addSubTxInfo(TxleSubTransactionEnd.newBuilder().setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-001").setIsSuccessful(true).build());
                    transaction.addSubTxInfo(TxleSubTransactionEnd.newBuilder().setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-002").setIsSuccessful(true).build());
                    break;
                case 2:
                    transaction.addSubTxInfo(TxleSubTransactionEnd.newBuilder().setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-001").setIsSuccessful(true).build());
                    transaction.addSubTxInfo(TxleSubTransactionEnd.newBuilder().setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-002").setIsSuccessful(false).build());
                    break;
                case 3:
                    // 该场景测试说明：在测试前需破坏网络或数据库，使数据库secondaryDBSchema的sql“update txle_sample_merchant set balance = balance + 1 where id = 1”执行失败，
                    // 在执行结束事件前，恢复网络或数据库，即可重试成功
                    transaction.addSubTxInfo(TxleSubTransactionEnd.newBuilder().setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-001").setIsSuccessful(true).build());
                    transaction.addSubTxInfo(TxleSubTransactionEnd.newBuilder().setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-002")
                            .setIsSuccessful(false).build());
                    // 重试成功场景：此处特意设置成功结果为false，触发重试，但由于实际【"update txle_sample_merchant set balance = balance + 1 where id = 1"】执行成功，故需执行其反向SQL以保证数据一致性
                    this.secondaryCustomService.executeUpdate("update txle_sample_merchant set balance = balance - 1 where id = 1");
                    break;
                case 4:
                    transaction.addSubTxInfo(TxleSubTransactionEnd.newBuilder().setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-001").setIsSuccessful(true).build());
                    transaction.addSubTxInfo(TxleSubTransactionEnd.newBuilder().setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-002").setIsSuccessful(false).build());
                    break;
                case 5:
                    transaction.addSubTxInfo(TxleSubTransactionEnd.newBuilder().setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-001").setIsSuccessful(true).build());
                    transaction.addSubTxInfo(TxleSubTransactionEnd.newBuilder().setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-002").setIsSuccessful(true).build());
                    transaction.addSubTxInfo(TxleSubTransactionEnd.newBuilder().setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-003").setIsSuccessful(true).build());
                    transaction.addSubTxInfo(TxleSubTransactionEnd.newBuilder().setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-004").setIsSuccessful(true).build());
                    break;
                case 6:
                    transaction.addSubTxInfo(TxleSubTransactionEnd.newBuilder().setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-001").setIsSuccessful(true).build());
                    transaction.addSubTxInfo(TxleSubTransactionEnd.newBuilder().setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-002").setIsSuccessful(true).build());
                    break;
                case 61:
                    transaction.addSubTxInfo(TxleSubTransactionEnd.newBuilder().setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-001").setIsSuccessful(true).build()).setIsCanOver(false);
                    break;
                case 62:
                    transaction.addSubTxInfo(TxleSubTransactionEnd.newBuilder().setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-002").setIsSuccessful(true).build()).setIsCanOver(false);
                    break;
                case 63:
                    transaction.addSubTxInfo(TxleSubTransactionEnd.newBuilder().setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-003").setIsSuccessful(true).build()).setIsCanOver(true);
                    break;
                case 7:
                    transaction.addSubTxInfo(TxleSubTransactionEnd.newBuilder().setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-001").setIsSuccessful(true).build());
                    transaction.addSubTxInfo(TxleSubTransactionEnd.newBuilder().setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-002").setIsSuccessful(true).build());
                    transaction.addSubTxInfo(TxleSubTransactionEnd.newBuilder().setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-003").setIsSuccessful(true).build());
                    transaction.addSubTxInfo(TxleSubTransactionEnd.newBuilder().setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-004").setIsSuccessful(true).build());
                    break;
                case 8:
                    transaction.addSubTxInfo(TxleSubTransactionEnd.newBuilder().setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-001").setIsSuccessful(true).build());
                    transaction.addSubTxInfo(TxleSubTransactionEnd.newBuilder().setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-002").setIsSuccessful(false).build());
                    break;
                case 9:
                    transaction.addSubTxInfo(TxleSubTransactionEnd.newBuilder().setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-001").setIsSuccessful(true).build());
                    transaction.addSubTxInfo(TxleSubTransactionEnd.newBuilder().setLocalTxId("sub-tx-" + currentGlobalTxIdIndex + "-002").setIsSuccessful(true).build());
                    break;
                default:
                    return;
            }

            TxleTxEndAck txEndAck = stubBlockingService.onEndTransaction(transaction.build());
            if (TxleTxEndAck.TransactionStatus.ABORTED.equals(txEndAck.getStatus())) {
                LOG.error("Occur an exception when ending global transaction [" + globalTxId + "].");
            } else {
                LOG.info("Successfully started global transaction [" + globalTxId + "].");
            }
            if (scene == 61) {
                restTemplate.postForObject("http://127.0.0.1:8008/startTransaction/{scene}/{globalTxId}", null, String.class, 62, transaction.getGlobalTxId());
            } else if (scene == 62) {
                restTemplate.postForObject("http://127.0.0.1:8008/startTransaction/{scene}/{globalTxId}", null, String.class, 63, transaction.getGlobalTxId());
            }
        } catch (Exception e) {
            LOG.error("Occur an exception when ending global transaction [{}].", globalTxId, e);
        }
    }

    public void onSynDatabaseFullDose() {
        try {
            long timestamp = System.currentTimeMillis();
            this.synDatabase(true, timestamp, primaryDBSchema, "txle_sample_user", "primary");
            this.synDatabase(true, timestamp, secondaryDBSchema, "txle_sample_merchant", "second");

            // 全量同步成功后，才启动增量同步
            this.onSynDatabase();
        } catch (RuntimeException e) {
            LOG.error("Failed to synchronize the full-dose data.", e);
            throw new RuntimeException(e);
        }
    }

    public void onSynDatabase() {
        while (true) {
            try {
                Thread.sleep(30000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            long timestamp = System.currentTimeMillis();
            this.synDatabase(false, timestamp, primaryDBSchema, "txle_sample_user", "primary");
            this.synDatabase(false, timestamp, secondaryDBSchema, "txle_sample_merchant", "second");
        }
    }

    private void synDatabase(boolean isFullDose, long timestamp, String dbSchema, String tableName, String dataSource) {
        // 模拟第三方简易程序，此处仅检测两张表，而非整库
        String tempMD5Info = getMD5Digest(dbSchema, tableName), tableNameWithSchema = dbSchema + "." + tableName;
        String dbMD5Info = dbMD5InfoMap.get(tableNameWithSchema);
        if (dbMD5Info == null || !dbMD5Info.equals(tempMD5Info)) {
            System.err.println("数据库表" + dbSchema + "." + tableName + "表结构发生变化。。。。" + System.currentTimeMillis());

            TxleBusinessDBInfo.Builder databaseSetBuilder = TxleBusinessDBInfo.newBuilder();
            TxleBusinessTable.Builder table = TxleBusinessTable.newBuilder().setName(tableName);
            List columnList;
            String sql = "SELECT column_name, data_type, column_key FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
            if ("primary".equals(dataSource)) {
                columnList = this.primaryCustomService.executeQuery(sql, dbSchema, tableName);
            } else {
                columnList = this.secondaryCustomService.executeQuery(sql, dbSchema, tableName);
            }

            if (columnList != null && !columnList.isEmpty()) {
                columnList.forEach(column -> {
                    Object[] columns = (Object[]) column;
                    table.addField(TxleBusinessField.newBuilder().setName(columns[0].toString()).setType(columns[1].toString()).setIsPrimaryKey("PRI".equals(columns[2])).build());
                });
            }

            TxleBusinessDatabase database0 = TxleBusinessDatabase.newBuilder().setName(dbSchema).addTable(0, table.build()).build();
            TxleBusinessNode node = TxleBusinessNode.newBuilder().setId("10.186.62.75").addDatabase(0, database0).build();

            databaseSetBuilder.addNode(node);
            databaseSetBuilder.setTimestamp(timestamp);
            databaseSetBuilder.setIsFullDose(isFullDose);

            TxleBasicAck txleBasicAck = stubBlockingService.onSynDatabase(databaseSetBuilder.build());
            System.err.println("syn " + dbSchema + " - txleBasicAck: received = " + txleBasicAck.getIsReceived() + ", result = " + txleBasicAck.getIsSuccessful());
            // txle端同步成功才赋值，若同步不成功，则不赋值即后续还会尝试本次同步
            if (txleBasicAck.getIsSuccessful()) {
                dbMD5InfoMap.put(tableNameWithSchema, tempMD5Info);
            }
        }
    }

    private String getMD5Digest(String dbSchema, String tableName) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");

            List dbInfoList = primaryCustomService.executeQuery("SELECT table_name, column_name, column_default, is_nullable, column_type, extra FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = '" + dbSchema + "' AND TABLE_NAME = '" + tableName + "'");
            md5.update(convertToByteFromList(dbInfoList));

            byte[] digest = md5.digest();
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                int v = digest[i] & 0xFF;
                if (v < 16) {
                    hex.append(0);
                }
                hex.append(Integer.toString(v, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        throw new RuntimeException("Failed to get MD5 info for database. schema = " + dbSchema);
    }

    private byte[] convertToByteFromList(List list) throws IOException {
        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        ObjectOutputStream out = null;
        try {
            out = new ObjectOutputStream(byteOut);
            for (Object obj : list) {
                out.writeObject(obj);
            }
            return byteOut.toByteArray();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            try {
                byteOut.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

}
