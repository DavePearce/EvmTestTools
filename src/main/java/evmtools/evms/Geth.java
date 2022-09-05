/*
 * Copyright 2022 ConsenSys Software Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software dis-
 * tributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package evmtools.evms;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import evmtools.core.Environment;
import evmtools.core.Trace;
import evmtools.core.Transaction;
import evmtools.core.WorldState;
import evmtools.util.AbstractExecutable;
import evmtools.util.Hex;

/**
 * An interface to Geth's command-line <code>evm</code> tool. This allows us to
 * execute a state test and get back the trace data. Essentially, this class
 * manages two tasks: (1) executing the tool as a (sub) process; (2) marshing
 * the state test data into and out of the tool.
 *
 * @author David J. Pearce
 *
 */
public class Geth extends AbstractExecutable {
	/**
	 * Command to use to execture Geth.
	 */
	private final String cmd = "evm";

	public Geth setTimeout(int timeout) {
		this.timeout = timeout;
		return this;
	}

	/**
	 * Execute a given transaction using the EVM.
	 *
	 * @param tx
	 * @return
	 */
	public Trace run(Environment env, WorldState pre, Transaction tx) throws JSONException {
		String preStateFile = null;
		try {
			preStateFile = createPreStateFile(env,pre,tx);
			// Build up the command
			ArrayList<String> command = new ArrayList<>();
			command.add(cmd);
			command.add("--json");
			command.add("--input");
			command.add(Hex.toHexString(tx.data));
			command.add("--nomemory=false");
			command.add("--nostorage=false");
			command.add("--prestate");
			command.add(preStateFile);
			command.add("--value");
			command.add(Hex.toHexString(tx.value));
			command.add("--gas");
			command.add(Hex.toHexString(tx.gasLimit));
			command.add("--price");
			command.add(Hex.toHexString(tx.gasPrice));
			command.add("--sender");
			command.add(Hex.toHexString(tx.sender));
			command.add("--receiver");
			command.add(Hex.toHexString(tx.to));
			command.add("--code");
			command.add(Hex.toHexString(tx.getCode(pre)));
			command.add("run");
			//
			String out = exec(command);
			//
			if(out != null) {
				return parseTraceOutput(new Scanner(out));
			} else {
				// Geth failed for some reason, so dump the input to help debugging.
				//System.out.println(pre.toJSON().toString(2));
				return null;
			}
		} catch (IOException e) {
			return null;
		} catch (InterruptedException e) {
			return null;
		} finally {
			if (preStateFile != null) {
				// delete the temporary file
				new File(preStateFile).delete();
			}
		}
	}

	public Trace runt8n(String fork, Environment env, WorldState pre, Transaction tx) throws JSONException, IOException {
		Path tempDir = null;
		String envFile = null;
		String allocFile = null;
		String txsFile = null;
		try {
			tempDir = createTemporaryDirectory();
			System.out.println("GOT: " + pre.toJSON().toString());
			envFile = createEnvFile(tempDir,env);
			allocFile = createAllocFile(tempDir,pre);
			txsFile = createTransactionsFile(tempDir,tx);
			// Build up the command
			ArrayList<String> command = new ArrayList<>();
			command.add(cmd);
			command.add("t8n");
			command.add("--state.fork");
			command.add(fork);
			// Trace info
			command.add("--trace");
			//command.add("--trace.memory");
//			command.add("--trace.nostorage");
//			command.add("false");
			// Input
			command.add("--input.env");
			command.add(envFile);
			command.add("--input.alloc");
			command.add(allocFile);
			command.add("--input.txs");
			command.add(txsFile);
			command.add("--output.basedir");
			command.add(tempDir.toString());
//			command.add("--output.result");
//			command.add("stdout");
			command.add("--output.alloc");
			command.add("alloc-out.json");
			//
			System.out.println("COMMAND: " + command);
			String out = exec(command);
			System.out.println("SYSOUT: " + out);
			//
			if(out != null) {
				// Parse into JSON. Geth produces one line per trace element.
				return readTraceFile(tempDir);
			} else {
				// Geth failed for some reason, so dump the input to help debugging.
				//System.out.println(pre.toJSON().toString(2));
				return null;
			}
		} catch (IOException e) {
			return null;
		} catch (InterruptedException e) {
			return null;
		} finally {
			forceDelete(tempDir);
		}
	}

	// ===============================================================================
	// Parsers for trace output
	// ===============================================================================

	private static Trace readTraceFile(Path dir) throws IOException {
		List<Trace> tr = new ArrayList<>();
		//
		Files.walk(dir, 10).forEach(f -> {
			try {
				if (f.toString().endsWith(".jsonl")) {
					if (tr.size() > 1) {
						throw new IllegalArgumentException("multiple trace files detected");
					}
					tr.add(parseTraceOutput(new Scanner(f)));
				}
			} catch (JSONException e) {
				throw new RuntimeException("Unable to parse JSON trace file");
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
		return tr.get(0);
	}

	private static Trace parseTraceOutput(Scanner scanner) throws JSONException {
		// Parse into JSON. Geth produces one line per trace element.
		ArrayList<Trace.Element> elements = new ArrayList<>();
		while (scanner.hasNextLine()) {
			String line = scanner.nextLine();
			JSONObject element = new JSONObject(line);
			if(!shouldIgnore(element)) {
				elements.add(Trace.Element.fromJSON(element));
			}
		}
		scanner.close();
		return new Trace(elements);
	}

	private static boolean shouldIgnore(JSONObject element) throws JSONException {
		// NOTE: this is basically needed to work around some issues with Geth. For
		// example, certain errors produce rather inconsistent output. For example, an
		// invalid jump destination ends up with two steps in the trace (of which we can
		// ignore the latter).
		if(element.has("pc") && element.has("error")) {
			return element.getString("error").equals("invalid jump destination");
		}
		//
		return false;
	}

	private static String createAllocFile(Path dir, WorldState pre)
			throws JSONException, IOException, InterruptedException {
		JSONObject json = pre.toJSON();
		byte[] bytes = json.toString(2).getBytes();
		return createTemporaryFile(dir, "alloc.json", bytes);
	}

	private static String createEnvFile(Path dir, Environment env)
			throws JSONException, IOException, InterruptedException {
		JSONObject json = env.toJSON();
		byte[] bytes = json.toString(2).getBytes();
		return createTemporaryFile(dir, "env.json", bytes);
	}

	private static String createTransactionsFile(Path dir, Transaction tx)
			throws JSONException, IOException, InterruptedException {
		JSONObject obj = tx.toJSON();
		obj.put("v","");
		obj.put("r","");
		obj.put("s","");
		JSONArray json = new JSONArray();
		json.put(0,obj);
		byte[] bytes = json.toString(2).getBytes();
		return createTemporaryFile(dir, "txs.json", bytes);
	}

	private static Path createTemporaryDirectory() throws IOException {
		return Files.createTempDirectory("geth");
	}

	private static String createPreStateFile(Environment env, WorldState pre, Transaction tx)
			throws JSONException, IOException, InterruptedException {
		JSONObject json = tx.toJSON();
		//
		json.put("alloc",pre.toJSON());
		json.put("coinbase", Hex.toHexString(env.currentCoinbase));
		json.put("difficulty", Hex.toHexString(env.currentDifficulty));
		// FIXME: block number is hardcoded because we cannot create a gensis block with
		// a number > 0.
		//json.put("number", Hex.toHexString(env.currentNumber));
		json.put("number", "0x0");
		json.put("timestamp", Hex.toHexString(env.currentTimestamp));
		// FIXME: commented out because it appears (for reasons unknown) to affect the
		// amount of gas reported by the GAS bytecode.
//		json.put("gasLimit", Hex.toHexString(env.currentGasLimit));
		//json.put("pre",pre.toJSON());
		byte[] bytes = json.toString(2).getBytes();
		return createTemporaryFile("geth_prestate", ".json", bytes);
	}

	/**
     * Write a given string into a temporary file which can then be checked by boogie.
     *
     * @param contents
     * @return
     */
    private static String createTemporaryFile(String prefix, String suffix, byte[] contents)
            throws IOException, InterruptedException {
        // Create new file
        File f = File.createTempFile(prefix, suffix);
        // Open for writing
        FileOutputStream fout = new FileOutputStream(f);
        // Write contents to file
        fout.write(contents);
        // Done creating file
        fout.close();
        //
        return f.getAbsolutePath();
    }

	/**
     * Write a given string into a temporary file which can then be checked by boogie.
     *
     * @param contents
     * @return
     */
    private static String createTemporaryFile(Path dir, String name, byte[] contents)
            throws IOException, InterruptedException {
    	File f = dir.resolve(name).toFile();
        // Open for writing
        FileOutputStream fout = new FileOutputStream(f);
        // Write contents to file
        fout.write(contents);
        // Done creating file
        fout.close();
        //
        return f.getAbsolutePath();
    }

    /**
	 * Force a directory and all its contents to be deleted.
	 *
	 * @param path
	 * @throws IOException
	 */
	public void forceDelete(Path path) throws IOException {
		if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
			try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
				for (Path entry : entries) {
					forceDelete(entry);
				}
			}
		}
		if (path.toFile().exists()) {
			Files.delete(path);
		}
	}
}
