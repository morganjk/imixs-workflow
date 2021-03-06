/*******************************************************************************
 *  Imixs Workflow 
 *  Copyright (C) 2001, 2011 Imixs Software Solutions GmbH,  
 *  http://www.imixs.com
 *  
 *  This program is free software; you can redistribute it and/or 
 *  modify it under the terms of the GNU General Public License 
 *  as published by the Free Software Foundation; either version 2 
 *  of the License, or (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful, 
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of 
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU 
 *  General Public License for more details.
 *  
 *  You can receive a copy of the GNU General Public
 *  License at http://www.gnu.org/licenses/gpl.html
 *  
 *  Project: 
 *  	http://www.imixs.org
 *  	http://java.net/projects/imixs-workflow
 *  
 *  Contributors:  
 *  	Imixs Software Solutions GmbH - initial API and implementation
 *  	Ralph Soika - Software Developer
 *******************************************************************************/

package org.imixs.workflow.engine.plugins;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.script.ScriptException;

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.RuleEngine;
import org.imixs.workflow.exceptions.PluginException;

/**
 * The Imixs Rule Plugin evaluates a business rule provided by the current
 * ActiviyEntity.
 * 
 * A business rule can be written in any script language supported by the JVM.
 * The Script Language is defined by the property 'txtBusinessRuleEngine' from
 * the current Event element. The script is defined by the property
 * 'txtBusinessRule'.
 * 
 * The Script can access all basic item values from the current workItem and
 * also the event by the provided JSON objects 'workitem' and 'event'.
 * 
 * <code>
 *  // test first value of the workitem attribute 'txtname'
 *  var isValid = ('Anna'==workitem.txtname[0]);
 * </code>
 * 
 * A script can add new values for the current workitem by providing the JSON
 * object 'result'.
 * 
 * <code>
 *     var result={ someitem:'Hello World', somenumber:1};
 * </code>
 * 
 * Also change values of the event object can be made by the script. These
 * changes will be reflected back for further processing.
 * 
 * <code>
 *  // disable mail 
 *   event.keymailenabled='0';
 * </code>
 * 
 * A script can set the variables 'isValid' and 'followUp' to validate a
 * workItem or set a new followUp activity.
 * 
 * <code>
 *   result={ isValid:false };
 * </code>
 * 
 * If the script set the variable 'isValid' to false then the plugin throws a
 * PluginExcpetion. The Plugin evaluates the variables 'errorCode' and
 * errorMessage. If these variables are set by the Script then the
 * PluginException will be updates with the corresponding errorCode and the
 * 'errorMessage' as params[]. If no errorCode is set then the errorCode of the
 * PluginException will default to 'VALIDATION_ERROR'.
 * 
 * If the script set the variable 'followUp' the follow-up behavior of the
 * current ActivityEntity will be updated.
 * 
 * If a script can not be evaluated by the scriptEngin a PluginExcpetion with
 * the errorCode 'INVALID_SCRIPT' will be thrown.
 * 
 * NOTE: all variable names are case sensitive! All JSON object elements are
 * lower case!
 * 
 * @author Ralph Soika
 * @version 3.0
 * 
 */

public class RulePlugin extends AbstractPlugin {

	public static final String INVALID_SCRIPT = "INVALID_SCRIPT";
	public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
	private static Logger logger = Logger.getLogger(RulePlugin.class.getName());

	/**
	 * The run method evaluates a script provided by an activityEntity with the
	 * specified scriptEngine.
	 * 
	 * After successful evaluation the method verifies the object value 'isValid'.
	 * If isValid is false then the method throws a PluginException. In addition the
	 * method evaluates the object values 'errorCode' and 'errorMessage' which will
	 * be part of the PluginException.
	 * 
	 * If 'isValid' is true or undefined the method evaluates the object value
	 * 'followUp'. If a followUp value is defined by the script the method update
	 * the model follow up definition.
	 * 
	 * If a script changes properties of the activity entity the method will
	 * evaluate these changes and update the ItemCollection for further processing.
	 * 
	 */
	public ItemCollection run(ItemCollection adocumentContext, ItemCollection adocumentActivity)
			throws PluginException {

		// test if a business rule is defined
		String script = adocumentActivity.getItemValueString("txtBusinessRule");
		if ("".equals(script.trim()))
			return adocumentContext; // nothing to do

		String sEngineType = adocumentActivity.getItemValueString("txtBusinessRuleEngine");
		RuleEngine ruleEngine = new RuleEngine(sEngineType);

		ItemCollection result = ruleEngine.evaluateBusinessRule(script, adocumentContext, adocumentActivity);

		// support deprecated scripts without a 'result' JSON object ...
		if (result == null) {
			evaluateDeprecatedScript(ruleEngine, adocumentActivity);
		} else {
			// first we test for the isValid variable
			Boolean isValidActivity = true;
			// first test result object
			if (result.hasItem("isValid")) {
				isValidActivity = result.getItemValueBoolean("isValid");
				result.removeItem("isValid");
			}
			// if isValid==false then throw a PluginException
			if (isValidActivity != null && !isValidActivity) {
				// test if a error code is provided!
				String sErrorCode = VALIDATION_ERROR;
				Object oErrorCode = null;
				if (result.hasItem("errorCode")) {
					oErrorCode = result.getItemValueString("errorCode");
					result.removeItem("errorCode");
				}
				if (oErrorCode != null && oErrorCode instanceof String) {
					sErrorCode = oErrorCode.toString();
				}

				// next test for errorMessage (this can be a string or an array
				// of strings
				Object[] params = null;
				if (result.hasItem("errorMessage")) {
					params = result.getItemValue("errorMessage").toArray();
					result.removeItem("errorMessage");
				}
				// finally we throw the Plugin Exception
				throw new PluginException(RulePlugin.class.getName(), sErrorCode,
						"BusinessRule: validation failed - ErrorCode=" + sErrorCode, params);
			}

			// now test the variable 'followUp'
			Object followUp = null;
			// first test result object
			if (result.hasItem("followUp")) {
				followUp = result.getItemValueString("followUp");
				result.removeItem("followUp");
			}

			// If followUp is defined we update now the activityEntity....
			if (followUp != null) {
				// try to get double value...
				Double d = Double.valueOf(followUp.toString());
				Long followUpActivity = d.longValue();
				if (followUpActivity != null && followUpActivity > 0) {
					adocumentActivity.replaceItemValue("keyFollowUp", "1");
					adocumentActivity.replaceItemValue("numNextActivityID", followUpActivity);
				}
			}

			// if result has item values then we update now the current
			// workitem iterate over all entries

			for (Map.Entry<String, List<Object>> entry : result.getAllItems().entrySet()) {
				String itemName = entry.getKey();
				// skip fieldnames starting with '$'
				if (!itemName.startsWith("$")) {
					logger.fine("Update item '" + itemName + "'");
					adocumentContext.replaceItemValue(itemName, entry.getValue());
				}
			}
		}

		// Finally update the Activity entity. Values can be provided optional
		// by the script variable 'event'...
		updateEvent(ruleEngine, adocumentActivity);

		return adocumentContext;

	}

	/**
	 * This method evaluates the script result without a 'result' JSON object. This
	 * kind of scripts is marked as deprecated.
	 * 
	 * @param ruleEngine
	 * @param adocumentActivity
	 * @throws PluginException
	 */
	private void evaluateDeprecatedScript(RuleEngine ruleEngine, ItemCollection adocumentActivity)
			throws PluginException {
		// deprecated evaluation:
		logger.warning("Script is deprecated - use JSON object 'result'");
		// first we test for the isValid variable
		Boolean isValidActivity = true;

		// if isValid is not provided by result then we look for a
		// direct var definition (this is for backward compatibility of
		// older scripts)
		isValidActivity = (Boolean) ruleEngine.getScriptEngine().get("isValid");

		// if isValid==false then throw a PluginException....
		if (isValidActivity != null && !isValidActivity) {
			// test if a error code is provided!
			String sErrorCode = VALIDATION_ERROR;
			Object oErrorCode = null;

			// if errorCode is not provided by result then we look for a
			// direct var definition (this is for backward compatibility
			// of older scripts)
			oErrorCode = ruleEngine.getScriptEngine().get("errorCode");

			if (oErrorCode != null && oErrorCode instanceof String) {
				sErrorCode = oErrorCode.toString();
			}

			// next test for errorMessage (this can be a string or an array
			// of strings
			Object[] params = null;

			params = ruleEngine.evaluateNativeScriptArray("errorMessage");

			// finally we throw the Plugin Exception
			throw new PluginException(RulePlugin.class.getName(), sErrorCode,
					"BusinessRule: validation failed - ErrorCode=" + sErrorCode, params);
		}

		// now test the variable 'followUp'
		Object followUp = null;
		// first test result object

		// if followUp is not provided by result then we look for a
		// direct
		// var definition (this is for backward compatibility of older
		// scripts)
		followUp = ruleEngine.getScriptEngine().get("followUp");

		// If followUp is defined we update now the activityEntity....
		if (followUp != null) {
			// try to get double value...
			Double d = Double.valueOf(followUp.toString());
			Long followUpActivity = d.longValue();
			if (followUpActivity != null && followUpActivity > 0) {
				adocumentActivity.replaceItemValue("keyFollowUp", "1");
				adocumentActivity.replaceItemValue("numNextActivityID", followUpActivity);

			}
		}

	}

	/**
	 * This method compares the properties of the script element 'event' with the
	 * values of the current ActivityEntity. If a value has changed, then the method
	 * will update the Activity ItemCollection which can be used for further
	 * processing.
	 * 
	 * @param engine
	 * @param event
	 * @throws ScriptException
	 */
	@SuppressWarnings({ "rawtypes" })
	private void updateEvent(RuleEngine ruleEngine, ItemCollection event) {

		ItemCollection newEvent = ruleEngine.convertScriptVariableToItemCollection("event");

		for (Map.Entry<String, List<Object>> entry : event.getAllItems().entrySet()) {
			String key = entry.getKey();
			List<Object> value = entry.getValue();
			if (newEvent.hasItem(key)) {
				List newValue = newEvent.getItemValue(key);

				// compare object arrays with deepEquals....
				if (!Arrays.deepEquals(newValue.toArray(), value.toArray())) {
					logger.fine("update event property " + entry.getKey());
					event.replaceItemValue(key, newValue);
				}
			}
		}

	}

}
