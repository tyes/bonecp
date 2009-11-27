/**
 * 
 */
package com.jolbox.bonecp;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.classextension.EasyMock.createNiceMock;
import static org.easymock.classextension.EasyMock.replay;
import static org.easymock.classextension.EasyMock.reset;
import static org.easymock.classextension.EasyMock.makeThreadSafe;
import static org.easymock.classextension.EasyMock.verify;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.concurrent.ArrayBlockingQueue;

import org.apache.log4j.Logger;
import org.easymock.IAnswer;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.jolbox.bonecp.BoneCP;
import com.jolbox.bonecp.BoneCPConfig;
import com.jolbox.bonecp.ConnectionHandle;
import com.jolbox.bonecp.ConnectionPartition;
import com.jolbox.bonecp.PoolWatchThread;


/** Tests the functionality of the pool watch thread.
 * @author wwadge
 *
 */
public class TestPoolWatchThread {

	/** Mock handle. */
	private static ConnectionPartition mockPartition;
	/** Mock handle. */
	private static BoneCP mockPool;
	/** Class under test. */
	static PoolWatchThread testClass;
	/** Mock handle. */
	private static Logger mockLogger;
	/** Break out from infinite loop. */
	static boolean first = true;
	/** Mock handle. */
	private static BoneCPConfig mockConfig;

	/** Test class setup.
	 * @throws IllegalArgumentException
	 * @throws IllegalAccessException
	 * @throws SecurityException
	 * @throws NoSuchFieldException
	 * @throws ClassNotFoundException
	 */
	@BeforeClass
	public static void setup() throws IllegalArgumentException, IllegalAccessException, SecurityException, NoSuchFieldException, ClassNotFoundException{
		Class.forName("org.hsqldb.jdbcDriver");
		mockPartition = createNiceMock(ConnectionPartition.class);


    	mockConfig = createNiceMock(BoneCPConfig.class);
    	expect(mockConfig.getPreparedStatementsCacheSize()).andReturn(0).anyTimes();
    	mockPool = createNiceMock(BoneCP.class);
		
		testClass = new PoolWatchThread(mockPartition, mockPool);

		mockLogger = createNiceMock(Logger.class);
		makeThreadSafe(mockLogger, true);

		mockLogger.error(anyObject());
		expectLastCall().anyTimes();
		expect(mockLogger.isDebugEnabled()).andReturn(true).anyTimes();

		mockLogger.debug(anyObject());
		expectLastCall().anyTimes();

		Field field = testClass.getClass().getDeclaredField("logger");
		field.setAccessible(true);
		field.set(null, mockLogger);

	}



	/**
	 * Rest the mocks.
	 */
	@Before
	public void doReset(){
		reset(mockPartition, mockPool, mockLogger);
	}

	/** Tests the case where we cannot create more transactions.
	 * @throws InterruptedException
	 */
	@Test
	public void testRunFullConnections() throws InterruptedException{
		mockPartition.lockAlmostFullLock();
		expectLastCall().once();
		mockPartition.unlockAlmostFullLock();
		expectLastCall().anyTimes();

		expect(mockPartition.getMaxConnections()).andReturn(5).once();
		expect(mockPartition.getCreatedConnections()).andReturn(5).once();
		mockPartition.setUnableToCreateMoreTransactions(true);
		expectLastCall().once();
		mockPartition.almostFullWait();
		// just to break out of the loop
		expectLastCall().once().andThrow(new InterruptedException());

		replay(mockPartition, mockPool, mockLogger);
		testClass.run();
		verify(mockPartition);


	}


	/** Tests the normal state.
	 * @throws InterruptedException
	 * @throws SecurityException
	 * @throws NoSuchFieldException
	 * @throws IllegalArgumentException
	 * @throws IllegalAccessException
	 */
	@Test
	public void testRunCreateConnections() throws InterruptedException, SecurityException, NoSuchFieldException, IllegalArgumentException, IllegalAccessException{
		expect(mockLogger.isDebugEnabled()).andReturn(true).anyTimes();
		ArrayBlockingQueue<ConnectionHandle> fakeConnections = new ArrayBlockingQueue<ConnectionHandle>(5);
		mockPartition.almostFullWait();
		expectLastCall().anyTimes();
		expect(mockPartition.getMaxConnections()).andAnswer(new IAnswer<Integer>() {

			@Override
			public Integer answer() throws Throwable {
				if (first) {
					first=false;
					return 4;
				} 
				Field field = testClass.getClass().getDeclaredField("signalled");
				field.setAccessible(true);
				field.setBoolean(testClass, true);
				return 4;

			}
		}).anyTimes();
		
		expect(mockPartition.getFreeConnections()).andReturn(fakeConnections).anyTimes();


		expect(mockPartition.getAcquireIncrement()).andReturn(1);
		
		
		expect(mockPartition.getUrl()).andReturn(CommonTestUtils.url).anyTimes();
		expect(mockPartition.getPassword()).andReturn(CommonTestUtils.password).anyTimes();
		expect(mockPartition.getUsername()).andReturn(CommonTestUtils.username).anyTimes();
		mockPartition.addFreeConnection((ConnectionHandle)anyObject());
		expectLastCall().once();
    	expect(mockPool.getConfig()).andReturn(mockConfig).anyTimes();
    	replay(mockPool);
		replay(mockPartition, mockLogger);
		testClass.run();
		verify(mockPartition);
		
		// check exceptional cases
		reset(mockPartition, mockPool, mockLogger);
		resetSignalled();


		first = true;
		mockPartition.unlockAlmostFullLock();
		expectLastCall().once();
		
		mockPartition.lockAlmostFullLock();
		expectLastCall().andThrow(new RuntimeException());
		replay(mockPartition, mockLogger);
		try{
			testClass.run();
			Assert.fail("Exception should have been thrown");
		} catch (RuntimeException e){
			// do nothing
		}
		verify(mockPartition);

		
		// check case where creating new ConnectionHandle fails
		reset(mockPool, mockLogger, mockConfig);
		reset(mockPartition);
		resetSignalled();

		first = true;
		expect(mockPartition.getFreeConnections()).andReturn(fakeConnections).anyTimes();

		expect(mockPartition.getMaxConnections()).andAnswer(new IAnswer<Integer>() {

			@Override
			public Integer answer() throws Throwable {
				if (first) {
					first=false;
					return 4;
				} 
				Field field = testClass.getClass().getDeclaredField("signalled");
				field.setAccessible(true);
				field.setBoolean(testClass, true);
				return 4;

			}
		}).anyTimes();
		
		mockPartition.unlockAlmostFullLock();
		expectLastCall().once();
		
		mockPartition.lockAlmostFullLock();
		expectLastCall().once();

		expect(mockConfig.getPreparedStatementsCacheSize()).andAnswer(new IAnswer<Integer>() {
			
			@Override
			public Integer answer() throws Throwable {
				throw new SQLException();
				
			} 
		}).once();
		expect(mockPartition.getAcquireIncrement()).andReturn(1).anyTimes();

		mockLogger.error(anyObject());
		expectLastCall(); 
		
		replay(mockPartition, mockPool, mockLogger, mockConfig);
		testClass.run();
		verify(mockPartition);

	}


	/**
	 * @throws NoSuchFieldException
	 * @throws IllegalAccessException
	 */
	private void resetSignalled() throws NoSuchFieldException,
			IllegalAccessException {
		Field field = testClass.getClass().getDeclaredField("signalled");
		field.setAccessible(true);
		field.setBoolean(testClass, false);
	}
}