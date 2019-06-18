package io.mosip.kernel.tests;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.apache.log4j.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;
import org.testng.ITest;
import org.testng.ITestContext;
import org.testng.ITestResult;
import org.testng.Reporter;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.testng.asserts.SoftAssert;
import org.testng.internal.BaseTestMethod;
import org.testng.internal.TestResult;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Verify;

import io.mosip.kernel.service.ApplicationLibrary;
import io.mosip.kernel.service.AssertKernel;
import io.mosip.kernel.util.CommonLibrary;
import io.mosip.kernel.util.KernelAuthentication;
import io.mosip.kernel.util.TestCaseReader;
import io.mosip.service.BaseTestCase;
import io.restassured.response.Response;

public class SyncMDataWithKeyIndexRegCentId extends BaseTestCase implements ITest{

	public SyncMDataWithKeyIndexRegCentId() {
		super();
	}

	// Declaration of all variables
	private static Logger logger = Logger.getLogger(SyncMDataWithKeyIndexRegCentId.class);
	private final String moduleName = "kernel";
	private final String apiName = "SyncMDataWithKeyIndexRegCentId";
	private final Map<String, String> props = new CommonLibrary().readProperty("Kernel");
	private final String syncMdatawithRegCentIdKeyIndex = props.get("syncMdatawithRegCentIdKeyIndex").toString();

	protected String testCaseName = "";
	SoftAssert softAssert = new SoftAssert();
	boolean status = false;
	public JSONArray arr = new JSONArray();
	Response response = null;
	JSONObject responseObject = null;
	private AssertKernel assertions = new AssertKernel();
	private ApplicationLibrary applicationLibrary = new ApplicationLibrary();
	KernelAuthentication auth=new KernelAuthentication();
	String cookie=null;

	/**
	 * method to set the test case name to the report
	 * 
	 * @param method
	 * @param testdata
	 * @param ctx
	 */
	@BeforeMethod(alwaysRun=true)
	public  void getTestCaseName(Method method, Object[] testdata, ITestContext ctx) throws Exception {
		String object = (String) testdata[0];
		testCaseName = moduleName+"_"+apiName+"_"+object.toString();
		cookie=auth.getAuthForRegistrationAdmin();
	}

	/**
	 * This data provider will return a test case name
	 * 
	 * @param context
	 * @return test case name as object
	 */
	@DataProvider(name = "fetchData")
	public Object[][] readData(ITestContext context)
			throws JsonParseException, JsonMappingException, IOException, ParseException {
	
		return new TestCaseReader().readTestCases(moduleName + "/" + apiName, testLevel);
		}

		/**
		 * This fetch the value of the data provider and run for each test case
		 * 
		 * @param fileName
		 * @param object
		 * 
		 */
		@SuppressWarnings("unchecked")
		@Test(dataProvider = "fetchData", alwaysRun = true)
		public void syncMDataWithKeyIndexRegCentId(String testcaseName){
			logger.info("Test Case Name:" + testcaseName);

			// getting request and expected response jsondata from json files.
			JSONObject objectDataArray[] = new TestCaseReader().readRequestResponseJson(moduleName, apiName, testcaseName);

			JSONObject objectData = objectDataArray[0];
			responseObject = objectDataArray[1];
				String regcenterId = objectData.get("regcenterId").toString();
				HashMap<String, String> regId = new HashMap<String, String>();
				regId.put("regcenterId", regcenterId);
				objectData.remove("regcenterId");
					response = applicationLibrary.getWithPathQueryParam(syncMdatawithRegCentIdKeyIndex, regId, objectData, cookie);
		//This method is for checking the authentication is pass or fail in rest services
		new CommonLibrary().responseAuthValidation(response);
		
		// add parameters to remove in response before comparison like time stamp
			ArrayList<String> listOfElementToRemove = new ArrayList<String>();
			listOfElementToRemove.add("responsetime");
			listOfElementToRemove.add("lastSyncTime");
			status = assertions.assertKernel(response, responseObject, listOfElementToRemove);
			if (!status) {
				logger.debug(response);
			}
			Verify.verify(status);
			softAssert.assertAll();
	}

	@Override
	public String getTestName() {
		return this.testCaseName;
	}

	@AfterMethod(alwaysRun = true)
	public void setResultTestName(ITestResult result) {
		try {
			Field method = TestResult.class.getDeclaredField("m_method");
			method.setAccessible(true);
			method.set(result, result.getMethod().clone());
			BaseTestMethod baseTestMethod = (BaseTestMethod) result.getMethod();
			Field f = baseTestMethod.getClass().getSuperclass().getDeclaredField("m_methodName");
			f.setAccessible(true);
			f.set(baseTestMethod, testCaseName);
		} catch (Exception e) {
			Reporter.log("Exception : " + e.getMessage());
		}
	}

}