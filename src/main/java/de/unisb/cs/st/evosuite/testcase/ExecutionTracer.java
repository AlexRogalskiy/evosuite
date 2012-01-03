/*
 * Copyright (C) 2010 Saarland University
 * 
 * This file is part of EvoSuite.
 * 
 * EvoSuite is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 * 
 * EvoSuite is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser Public License along with
 * EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */

package de.unisb.cs.st.evosuite.testcase;

import java.util.Map;

import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.unisb.cs.st.evosuite.Properties;
import de.unisb.cs.st.evosuite.Properties.Criterion;
import de.unisb.cs.st.evosuite.coverage.concurrency.ConcurrencyTracer;
import de.unisb.cs.st.evosuite.coverage.concurrency.LockRuntime;
import de.unisb.cs.st.evosuite.javaagent.BooleanHelper;

/**
 * This class collects information about chosen branches/paths at runtime
 * 
 * @author Gordon Fraser
 * 
 */
public class ExecutionTracer {

	private static Logger logger = LoggerFactory.getLogger(ExecutionTracer.class);

	private static ExecutionTracer instance = null;

	/**
	 * We need to disable the execution tracer sometimes, e.g. when calling
	 * equals in the branch distance function
	 */
	private boolean disabled = true;

	/** Flag that is used to kill threads that are stuck in endless loops */
	private boolean killSwitch = false;

	private int num_statements = 0;

	private static final boolean testabilityTransformation = Properties.TT;

	private static boolean checkCallerThread = true;

	/**
	 * If a thread of a test case survives for some reason (e.g. long call to
	 * external library), then we don't want its data in the current trace
	 */
	private static Thread currentThread = null;

	public static void setThread(Thread thread) {
		currentThread = thread;
	}

	public static void disable() {
		ExecutionTracer tracer = ExecutionTracer.getExecutionTracer();
		tracer.disabled = true;
	}

	public static void enable() {
		ExecutionTracer tracer = ExecutionTracer.getExecutionTracer();
		tracer.disabled = false;
	}

	public static boolean isEnabled() {
		ExecutionTracer tracer = ExecutionTracer.getExecutionTracer();
		return !tracer.disabled;
	}

	public static void setKillSwitch(boolean value) {
		ExecutionTracer tracer = ExecutionTracer.getExecutionTracer();
		tracer.killSwitch = value;
	}

	public static void setCheckCallerThread(boolean checkCallerThread) {
		ExecutionTracer.checkCallerThread = checkCallerThread;
	}

	private ExecutionTrace trace;

	public static ExecutionTracer getExecutionTracer() {
		if (instance == null) {
			instance = new ExecutionTracer();
		}
		return instance;
	}

	/**
	 * Reset for new execution
	 */
	public void clear() {
		trace = new ExecutionTrace();
		BooleanHelper.clearStack();
		num_statements = 0;

		//#TODO steenbuck: We should be able to register us somewhere, so that we're called before run is executed
		if (Properties.CRITERION == Criterion.CONCURRENCY) {
			trace.concurrencyTracer = new ConcurrencyTracer();
			LockRuntime.tracer = trace.concurrencyTracer;
			checkCallerThread = false;
		}
	}

	/**
	 * Obviously more than one thread is executing during the creation of
	 * concurrent TestCases. #TODO steenbuck we should test if
	 * Thread.currentThread() is in the set of currently executing threads
	 * 
	 * @return
	 */
	private static boolean isThreadNeqCurrentThread() {
		if (!checkCallerThread) {
			return false;
		}
		if (getExecutionTracer().killSwitch) {
			logger.info("Raising TimeoutException as kill switch is active - passedLine");
			throw new TestCaseExecutor.TimeoutExceeded();
		}
		if (currentThread == null) {
			logger.warn("CurrentThread has not been set!");
			Map<Thread, StackTraceElement[]> map = Thread.getAllStackTraces();
			for (Thread t : map.keySet()) {
				System.err.println("Thread: " + t);
				for (StackTraceElement e : map.get(t)) {
					System.err.println(" -> " + e);
				}
			}
			currentThread = Thread.currentThread();
		}
		return Thread.currentThread() != currentThread;
	}

	/**
	 * Return trace of current execution
	 * 
	 * @return
	 */
	public ExecutionTrace getTrace() {
		trace.finishCalls();
		return trace;

		// ExecutionTrace copy = trace.clone();
		// // copy.finishCalls();
		// return copy;
	}

	/**
	 * Called by instrumented code whenever a new method is called
	 * 
	 * @param classname
	 * @param methodname
	 */
	public static void enteredMethod(String classname, String methodname, Object caller)
	        throws TestCaseExecutor.TimeoutExceeded {
		ExecutionTracer tracer = getExecutionTracer();

		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		if (testabilityTransformation) {
			BooleanHelper.methodEntered();
		}

		if (tracer.killSwitch) {
			logger.info("Raising TimeoutException as kill switch is active - enteredMethod");
			throw new TestCaseExecutor.TimeoutExceeded();
		}

		//logger.trace("Entering method " + classname + "." + methodname);
		tracer.trace.enteredMethod(classname, methodname, caller);
	}

	/**
	 * Called by instrumented code whenever a return values is produced
	 * 
	 * @param classname
	 * @param methodname
	 * @param value
	 */
	public static void returnValue(int value, String className, String methodName) {
		if (isThreadNeqCurrentThread())
			return;

		ExecutionTracer tracer = getExecutionTracer();
		if (tracer.disabled)
			return;

		//logger.trace("Return value: " + value);
		tracer.trace.returnValue(className, methodName, value);
	}

	/**
	 * Called by instrumented code whenever a return values is produced
	 * 
	 * @param classname
	 * @param methodname
	 * @param value
	 */
	public static void returnValue(Object value, String className, String methodName) {
		if (isThreadNeqCurrentThread())
			return;

		if (!ExecutionTracer.isEnabled())
			return;

		if (value == null) {
			returnValue(0, className, methodName);
			return;
		}
		StringBuilder tmp = null;
		try {
			// setLineCoverageDeactivated(true);
			// logger.warn("Disabling tracer: returnValue");
			ExecutionTracer.disable();
			tmp = new StringBuilder(value.toString());
		} catch (Throwable t) {
			return;
		} finally {
			ExecutionTracer.enable();
		}
		int index = 0;
		int position = 0;
		boolean found = false;
		boolean deleteAddresses = true;
		char c = ' ';
		// quite fast method to detect memory addresses in Strings.
		while ((position = tmp.indexOf("@", index)) > 0) {
			for (index = position + 1; index < position + 17 && index < tmp.length(); index++) {
				c = tmp.charAt(index);
				if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
				        || (c >= 'A' && c <= 'F')) {
					found = true;
				} else {
					break;
				}
			}
			if (deleteAddresses && found) {
				tmp.delete(position + 1, index);
			}
		}

		returnValue(tmp.toString().hashCode(), className, methodName);
	}

	/**
	 * Called by instrumented code whenever a method is left
	 * 
	 * @param classname
	 * @param methodname
	 */
	public static void leftMethod(String classname, String methodname) {
		ExecutionTracer tracer = getExecutionTracer();
		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		if (testabilityTransformation) {
			BooleanHelper.methodLeft();
		}

		tracer.trace.exitMethod(classname, methodname);
		// logger.trace("Left method " + classname + "." + methodname);
	}

	/**
	 * Called by the instrumented code each time a new source line is executed
	 * 
	 * @param line
	 */
	public static void checkTimeout() {
		ExecutionTracer tracer = getExecutionTracer();
		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		if (tracer.killSwitch) {
			logger.info("Raising TimeoutException as kill switch is active - passedLine");
			throw new TestCaseExecutor.TimeoutExceeded();
		}
	}

	/**
	 * Called by the instrumented code each time a new source line is executed
	 * 
	 * @param line
	 */
	public static void passedLine(String className, String methodName, int line) {
		ExecutionTracer tracer = getExecutionTracer();
		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		if (tracer.killSwitch) {
			logger.info("Raising TimeoutException as kill switch is active - passedLine");
			throw new TestCaseExecutor.TimeoutExceeded();
		}

		tracer.trace.linePassed(className, methodName, line);
	}

	/**
	 * Called by the instrumented code each time an unconditional branch is
	 * taken. This is not enabled by default, only some coverage criteria (e.g.,
	 * LCSAJ) use it.
	 * 
	 * 
	 * @param opcode
	 * @param branch
	 * @param btyecode_id
	 */
	public static void passedUnconditionalBranch(int opcode, int branch, int bytecode_id) {
		ExecutionTracer tracer = getExecutionTracer();
		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		// Add current branch to control trace
		tracer.trace.branchPassed(branch, bytecode_id, 0.0, 0.0);
	}

	/**
	 * Called by the instrumented code each time a new branch is taken
	 * 
	 * @param val
	 * @param opcode
	 * @param line
	 */
	public static void passedBranch(int val, int opcode, int branch, int bytecode_id) {

		ExecutionTracer tracer = getExecutionTracer();
		// logger.info("passedBranch val="+val+", opcode="+opcode+", branch="+branch+", bytecode_id="+bytecode_id);
		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		if (tracer.killSwitch) {
			logger.info("Raising TimeoutException as kill switch is active - passedLine");
			throw new TestCaseExecutor.TimeoutExceeded();
		}

		// logger.trace("Called passedBranch1 with opcode "+AbstractVisitor.OPCODES[opcode]+" and val "+val+" in branch "+branch);
		double distance_true = 0.0;
		double distance_false = 0.0;
		switch (opcode) {
		case Opcodes.IFEQ:
			distance_true = Math.abs((double) val); // The greater abs is, the
			// further away from 0
			distance_false = distance_true == 0 ? 1.0 : 0.0; // Anything but 0
			// is good
			break;
		case Opcodes.IFNE:
			distance_false = Math.abs((double) val); // The greater abs is, the
			// further away from 0
			distance_true = distance_false == 0 ? 1.0 : 0.0; // Anything but 0
			// leads to NE
			break;
		case Opcodes.IFLT:
			distance_true = val >= 0 ? val + 1.0 : 0.0; // The greater, the
			// further away from < 0
			distance_false = val < 0 ? 0.0 - val + 1.0 : 0.0; // The smaller,
			// the further
			// away from < 0
			break;
		case Opcodes.IFGT:
			distance_true = val <= 0 ? 0.0 - val + 1.0 : 0.0;
			distance_false = val > 0 ? val + 1.0 : 0.0;
			break;
		case Opcodes.IFGE:
			distance_true = val < 0 ? 0.0 - val + 1.0 : 0.0;
			distance_false = val >= 0 ? val + 1.0 : 0.0;
			break;
		case Opcodes.IFLE:
			distance_true = val > 0 ? val + 1.0 : 0.0; // The greater, the
			// further away from < 0
			distance_false = val <= 0 ? 0.0 - val + 1.0 : 0.0; // The smaller,
			// the further
			// away from < 0
			break;
		default:
			logger.error("Unknown opcode: " + opcode);

		}
		// logger.trace("1 Branch distance true : " + distance_true);
		// logger.trace("1 Branch distance false: " + distance_false);

		// Add current branch to control trace
		tracer.trace.branchPassed(branch, bytecode_id, distance_true, distance_false);
	}

	/**
	 * Called by the instrumented code each time a new branch is taken
	 * 
	 * @param val1
	 * @param val2
	 * @param opcode
	 * @param line
	 */
	public static void passedBranch(int val1, int val2, int opcode, int branch,
	        int bytecode_id) {
		ExecutionTracer tracer = getExecutionTracer();
		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		if (tracer.killSwitch) {
			logger.info("Raising TimeoutException as kill switch is active - passedLine");
			throw new TestCaseExecutor.TimeoutExceeded();
		}

		/* logger.trace("Called passedBranch2 with opcode "
		        + AbstractVisitor.OPCODES[opcode] + ", val1=" + val1 + ", val2=" + val2
		        + " in branch " + branch); */
		double distance_true = 0;
		double distance_false = 0;
		switch (opcode) {
		// Problem is that the JVM is a stack machine
		// x < 5 gets compiled to a val2 > val1,
		// because operators are on the stack in reverse order
		case Opcodes.IF_ICMPEQ:
			// The greater the difference, the further away
			distance_true = Math.abs((double) val1 - (double) val2);
			// Anything but 0 is good
			distance_false = distance_true == 0 ? 1.0 : 0.0;
			break;
		case Opcodes.IF_ICMPNE:
			// The greater abs is, the further away from 0
			distance_false = Math.abs((double) val1 - (double) val2);
			// Anything but 0 leads to NE
			distance_true = distance_false == 0 ? 1.0 : 0.0;
			break;
		case Opcodes.IF_ICMPLT:
			// val1 >= val2?
			distance_true = val1 >= val2 ? (double) val1 - (double) val2 + 1.0 : 0.0;
			distance_false = val1 < val2 ? (double) val2 - (double) val1 + 1.0 : 0.0;
			break;
		case Opcodes.IF_ICMPGE:
			// val1 < val2?
			distance_true = val1 < val2 ? (double) val2 - (double) val1 + 1.0 : 0.0;
			distance_false = val1 >= val2 ? (double) val1 - (double) val2 + 1.0 : 0.0;
			break;
		case Opcodes.IF_ICMPGT:
			// val1 <= val2?
			distance_true = val1 <= val2 ? (double) val2 - (double) val1 + 1.0 : 0.0;
			distance_false = val1 > val2 ? (double) val1 - (double) val2 + 1.0 : 0.0;
			break;
		case Opcodes.IF_ICMPLE:
			// val1 > val2?
			distance_true = val1 > val2 ? (double) val1 - (double) val2 + 1.0 : 0.0;
			distance_false = val1 <= val2 ? (double) val2 - (double) val1 + 1.0 : 0.0;
			break;
		default:
			logger.error("Unknown opcode: " + opcode);
		}
		// logger.trace("2 Branch distance true: " + distance_true);
		// logger.trace("2 Branch distance false: " + distance_false);

		// Add current branch to control trace
		tracer.trace.branchPassed(branch, bytecode_id, distance_true, distance_false);
		// tracer.trace.branchPassed(branch, distance_true, distance_false);

	}

	/**
	 * Called by the instrumented code each time a new branch is taken
	 * 
	 * @param val1
	 * @param val2
	 * @param opcode
	 * @param line
	 */
	public static void passedBranch(Object val1, Object val2, int opcode, int branch,
	        int bytecode_id) {
		ExecutionTracer tracer = getExecutionTracer();
		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		if (tracer.killSwitch) {
			logger.info("Raising TimeoutException as kill switch is active - passedLine");
			throw new TestCaseExecutor.TimeoutExceeded();
		}

		// logger.trace("Called passedBranch3 with opcode "
		//        + AbstractVisitor.OPCODES[opcode]); // +", val1="+val1+", val2="+val2+" in branch "+branch);
		double distance_true = 0;
		double distance_false = 0;
		// logger.warn("Disabling tracer: passedBranch with 2 Objects");

		switch (opcode) {
		case Opcodes.IF_ACMPEQ:
			if (val1 == null) {
				distance_true = val2 == null ? 0.0 : 1.0;
			} else {
				disable();
				try {
					distance_true = val1.equals(val2) ? 0.0 : 1.0;
				} catch (Throwable t) {
					logger.debug("Equality raised exception: " + t);
					distance_true = 1.0;
				} finally {
					enable();
				}
			}
			break;
		case Opcodes.IF_ACMPNE:
			if (val1 == null) {
				distance_true = val2 == null ? 1.0 : 0.0;
			} else {
				disable();
				try {
					distance_true = val1.equals(val2) ? 1.0 : 0.0;
				} catch (Exception e) {
					logger.debug("Caught exception during comparison: " + e);
					distance_true = 1.0;
				} finally {
					enable();
				}
			}
			break;
		}

		distance_false = distance_true == 0 ? 1.0 : 0.0;

		// Add current branch to control trace
		tracer.trace.branchPassed(branch, bytecode_id, distance_true, distance_false);
	}

	/**
	 * Called by the instrumented code each time a new branch is taken
	 * 
	 * @param val
	 * @param opcode
	 * @param line
	 */
	public static void passedBranch(Object val, int opcode, int branch, int bytecode_id) {
		ExecutionTracer tracer = getExecutionTracer();
		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		if (tracer.killSwitch) {
			logger.info("Raising TimeoutException as kill switch is active - passedLine");
			throw new TestCaseExecutor.TimeoutExceeded();
		}

		double distance_true = 0;
		double distance_false = 0;
		switch (opcode) {
		case Opcodes.IFNULL:
			distance_true = val == null ? 0.0 : 1.0;
			break;
		case Opcodes.IFNONNULL:
			distance_true = val == null ? 1.0 : 0.0;
			break;
		default:
			logger.error("Warning: encountered opcode " + opcode);
		}
		distance_false = distance_true == 0 ? 1.0 : 0.0;
		// enable();

		// logger.trace("Branch distance true: " + distance_true);
		// logger.trace("Branch distance false: " + distance_false);

		// Add current branch to control trace
		tracer.trace.branchPassed(branch, bytecode_id, distance_true, distance_false);
	}

	/**
	 * Called by instrumented code each time a variable gets written to (a
	 * Definition)
	 */
	public static void passedDefinition(Object caller, int defID) {
		if (isThreadNeqCurrentThread())
			return;

		ExecutionTracer tracer = getExecutionTracer();
		if (!tracer.disabled)
			tracer.trace.definitionPassed(caller, defID);
	}

	/**
	 * Called by instrumented code each time a variable is read from (a Use)
	 */
	public static void passedUse(Object caller, int useID) {

		ExecutionTracer tracer = getExecutionTracer();
		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		tracer.trace.usePassed(caller, useID);
	}

	public static void passedMutation(int mutationId, double distance) {
		ExecutionTracer tracer = getExecutionTracer();
		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		if (tracer.killSwitch) {
			logger.info("Raising TimeoutException as kill switch is active - passedLine");
			throw new TestCaseExecutor.TimeoutExceeded();
		}

		tracer.trace.mutationPassed(mutationId, distance);
	}

	public static void statementExecuted() {
		ExecutionTracer tracer = getExecutionTracer();
		if (tracer.disabled)
			return;

		if (isThreadNeqCurrentThread())
			return;

		if (tracer.killSwitch) {
			logger.info("Raising TimeoutException as kill switch is active - passedLine");
			throw new TestCaseExecutor.TimeoutExceeded();
		}

		tracer.num_statements++;
	}

	public int getNumStatementsExecuted() {
		return num_statements;
	}

	private ExecutionTracer() {
		trace = new ExecutionTrace();
	}
}
