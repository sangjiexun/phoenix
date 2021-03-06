package com.dianping.kernel.biz;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;

import junit.framework.Assert;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class BizWarTest {
	private BizWar war;

	public BizWarTest(String artifact, BizWar war) {
		this.war = war;
	}

//	@Parameters(name = "{0}")
	@Parameters
	public static Collection<Object[]> getBizWarToTest() {
		Collection<Object[]> wars = new ArrayList<Object[]>();
		InputStream warsIn = BizWarTest.class.getResourceAsStream("wars.properties");
		BufferedReader reader = new BufferedReader(new InputStreamReader(warsIn));
		String line;

		if(System.getProperty("os.name").toLowerCase().contains("windows")) {
			System.out.println("Windows is not supported yet, skip test");
		}
		
		try {
			while ((line = reader.readLine()) != null) {
				if(line.trim().length() == 0 || line.trim().startsWith("#")) {
					continue;
				}
				String[] parts = line.trim().split("\\s+");
				if (parts.length != 3) {
					throw new RuntimeException(String.format("wars.properties has invalid line: %s", line));
				}
				Object[] war = new Object[] { parts[1], new BizWar(parts[0], parts[1], parts[2]) };
				wars.add(war);
			}
		} catch (IOException e) {
			throw new RuntimeException("wars.properties not found", e);
		}

		return wars;
	}

	@Test
	public void testBizWar() throws Exception {
		System.out.println(String.format("Testing war: %s", war));
		File testClassesDir = new File(this.getClass().getClassLoader().getResource(".").getPath());
		File phoenixBaseDir = testClassesDir.getParentFile().getParentFile().getParentFile();
		DefaultExecutor executor = new DefaultExecutor();
		CommandLine cmd = new CommandLine(phoenixBaseDir + "/misc/integration_test.sh");
		cmd.addArgument("-g" + war.getGroupId());
		cmd.addArgument("-a" + war.getArtifactId());
		cmd.addArgument("-v" + war.getVersion());
		cmd.addArgument("-ctomcat");
		int exitCode = executor.execute(cmd);
		Assert.assertEquals(0, exitCode);
	}
}
