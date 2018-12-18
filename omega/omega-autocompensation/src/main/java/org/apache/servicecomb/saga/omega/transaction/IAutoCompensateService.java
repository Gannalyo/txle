package org.apache.servicecomb.saga.omega.transaction;

/**
 * Business interface for auto-compensation.
 * 
 * @author Gannalyo
 * @since 2018-07-30
 */
public interface IAutoCompensateService {

	/**
	 * To execute auto-compensation SQL in case of system exception. 
	 * 
	 * @param globalTxId Global Transaction Identify
	 * @param localTxId Sub-transaction Identify
	 * @return result
	 * @author Gannalyo
	 * @since 2018-07-30
	 */
	public boolean executeAutoCompensateByLocalTxId(String globalTxId, String localTxId);

}
