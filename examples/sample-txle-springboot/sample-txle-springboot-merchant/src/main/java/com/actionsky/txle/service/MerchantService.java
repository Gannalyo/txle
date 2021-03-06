/*
 * Copyright (c) 2018-2019 ActionTech.
 * License: http://www.apache.org/licenses/LICENSE-2.0 Apache License 2.0 or higher.
 */

package com.actionsky.txle.service;

import com.actionsky.txle.entity.MerchantEntity;
import com.actionsky.txle.grpc.IntegrateTxleService;
import com.actionsky.txle.repository.MerchantRepository;
import com.google.gson.JsonObject;
import org.apache.servicecomb.saga.omega.context.OmegaContext;
import org.apache.servicecomb.saga.omega.transaction.KafkaMessage;
import org.apache.servicecomb.saga.omega.transaction.MessageSender;
import org.apache.servicecomb.saga.omega.transaction.accidentplatform.AccidentHandleType;
import org.apache.servicecomb.saga.omega.transaction.accidentplatform.ClientAccidentHandlingService;
import org.apache.servicecomb.saga.omega.transaction.annotations.AutoCompensable;
import org.apache.servicecomb.saga.omega.transaction.annotations.Compensable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.invoke.MethodHandles;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

/**
 * @author Gannalyo
 * @since 2019/3/29
 */
@Service
public class MerchantService {
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private ClientAccidentHandlingService clientAccidentHandlingService;

    @Autowired(required = false)
    private OmegaContext omegaContext;

    @Autowired
    private MessageSender messageSender;

    @Value("${spring.datasource.driver-class-name:\"\"}")
    private String drivername;

    @Value("${spring.datasource.url:\"\"}")
    private String dburl;

    @Value("${spring.datasource.username:\"\"}")
    private String dbusername;

    @Autowired
    private IntegrateTxleService integrateTxleService;

    @Transactional
    @Compensable(compensationMethod = "updateBalanceByMerchantIdRollback")
    public int updateBalanceById(@Param("merchantid") long merchantid, @Param("balance") double balance) {
        int result = merchantRepository.updateBalanceById(merchantid, balance);
        // 手动补偿场景：由业务人员自行收集所影响的数据信息
        messageSender.reportMessageToServer(new KafkaMessage(drivername, dburl, dbusername, "txle_sample_merchant", "update", merchantid + ""));
        if (balance > 200) {
            throw new RuntimeException("The 'Merchant' Service threw a runtime exception in case of balance was more than 200.");
        }
        return result;
    }

    public int updateBalanceByMerchantIdRollback(@Param("merchantid") long merchantid, @Param("balance") double balance) {
        try {
            LOG.error("Executing method 'updateBalanceByUserIdRollback'.");
            int result = merchantRepository.updateBalanceById(merchantid, -balance);
            if (result < 1) {
                LOG.error("Failed to execute 'merchantRepository.updateBalanceById([{}], -[{}])'.", merchantid, balance);
            }
            return result;
        } catch (Exception e) {
            JsonObject jsonParams = new JsonObject();
            try {
                jsonParams.addProperty("type", AccidentHandleType.ROLLBACK_ERROR.toInteger());
                jsonParams.addProperty("globaltxid", omegaContext.globalTxId());
                jsonParams.addProperty("localtxid", omegaContext.localTxId());
                jsonParams.addProperty("remark", "Failed to execute Compensable SQL [UPDATE MerchantEntity T SET T.balance = T.balance + " + balance + " WHERE id = " + merchantid + "].");
                MerchantEntity merchantEntity = null;
                try {
                    merchantEntity = merchantRepository.findOne(merchantid);
                } catch (Exception e1) {
                    // 如果被补偿的接口是因为数据库连接数过大等数据库原因，那么此处findOne方法也会执行失败，所以捕获下
                    LOG.error("Failed to execute method 'merchantRepository.findOne([{}])', params [{}].", merchantid, jsonParams.toString(), e1);
                }
                if (merchantEntity != null) {
                    jsonParams.addProperty("bizinfo", merchantEntity.toJsonString());
                } else {
                    jsonParams.addProperty("bizinfo", "{\"merchantid\": " + merchantid + "}");
                }
                LOG.error("Failed to execute method 'updateBalanceByMerchantIdRollback', params [{}].", jsonParams.toString(), e);
                clientAccidentHandlingService.reportMsgToAccidentPlatform(jsonParams.toString());
            } catch (Exception e2) {
                LOG.error("Failed to report accident for method 'updateBalanceByMerchantIdRollback', params [{}].", jsonParams.toString(), e2);
            }
            // 不要抛出异常，否则org.apache.servicecomb.saga.omega.context.CompensationContext中报(IllegalAccessException | InvocationTargetException)错误
        }
        return 0;
    }

    @Transactional
    @AutoCompensable
    public int updateBalanceByIdAuto(@Param("merchantid") long merchantid, @Param("balance") double balance) {
        if (balance == 2) {
            throw new RuntimeException("Merchant auto normal throw exception");
        }
        return merchantRepository.updateBalanceById(merchantid, balance);
    }

    @Transactional
    @Compensable(compensationMethod = "highPerformanceRollback")
    public void highPerformance() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SSS");
        LOG.error("[" + sdf.format(new Date()) + "] Executing method '" + this.getClass() + ".highPerformance'.");
    }

    public void highPerformanceRollback() {
    }

    @Transactional
    public boolean updateBalanceInGRPCIntegration(String xid, boolean isCanOver, long merchantid, double balance) {
        // 原业务SQL无需在此处执行，在全局事务启动成功后，与备份SQL一同执行
//        int result = merchantRepository.updateBalanceById(merchantid, balance);
        String localTxId = UUID.randomUUID().toString(), sql = "UPDATE txle_sample_merchant T SET T.balance = T.balance + " + balance + " WHERE id = '" + merchantid + "'";
        boolean result = this.integrateTxleService.executeSqlUnderTxleTransaction(xid, isCanOver, localTxId, sql, 0, 0);
        // 手动补偿场景：由业务人员自行收集所影响的数据信息
        messageSender.reportMessageToServer(new KafkaMessage(drivername, dburl, dbusername, "txle_sample_merchant", "update", merchantid + ""));
        if (balance > 200) {
            throw new RuntimeException("The 'Merchant' Service threw a runtime exception in case of balance was more than 200.");
        }
        return result;
    }
}
