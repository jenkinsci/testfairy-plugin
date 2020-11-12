package org.jenkinsci.plugins.testfairy;

import com.testfairy.uploader.TestFairyException;
import com.testfairy.uploader.Uploader;
import com.testfairy.uploader.Validation;
import hudson.EnvVars;
import hudson.model.AbstractBuild;
import hudson.scm.ChangeLogSet;
import jenkins.model.ArtifactManager;
import jenkins.util.VirtualFile;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jfree.io.FileUtilities;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.util.List;

public class Utils implements Serializable {
	private static final String CHANGE_LOG_FILE = "testfairy_change_log";

	public static String extractChangeLog(AbstractBuild<?, ?> build, EnvVars vars, final ChangeLogSet<?> changeSet, PrintStream logger) {
		ArtifactManager artifactManager = build.getArtifactManager();

		try {
			VirtualFile[] list = artifactManager.root().list();
			for(VirtualFile virtualFile : list) {
				if(virtualFile.canRead() && virtualFile.getName().equals(CHANGE_LOG_FILE)) {
					InputStream stream = virtualFile.open();
					try {
						List<String> lines = IOUtils.readLines(stream, Charset.defaultCharset());
						String text = StringUtils.join(lines, System.getProperty("line.separator"));
						logger.println("Loading custom change log from artifacts");
						return text;
					} catch(IOException ignored) {
					} finally {
						if(stream != null) {
							IOUtils.closeQuietly(stream);
						}
					}
				}
			}
		} catch(IOException ignored) {
		}

		String fileName = vars.expand("$JENKINS_HOME") + File.separator +
				    "jobs" + File.separator +
				    vars.expand("$JOB_NAME") + File.separator +
				    "builds" + File.separator +
				    vars.expand("$BUILD_ID") + File.separator +
				    CHANGE_LOG_FILE;

		String changeLog = getChangeLogFromFile(fileName);

		if (changeLog != null && !changeLog.isEmpty()) {
			logger.println("Loading custom changeLog from " + fileName);
			return changeLog;
		} else {
			logger.println("Loading changeLog from source control");
			return getChangeLogFromSourceControl(changeSet);
		}
	}

	private static String getChangeLogFromSourceControl(ChangeLogSet<?> changeSet) {
		StringBuilder stringBuilder = new StringBuilder();
		stringBuilder.append("\n\n")
		    .append(changeSet.isEmptySet() ? "No changes since last build" : "Changes")
		    .append("\n");

		int entryNumber = 1;

		for (ChangeLogSet.Entry entry : changeSet) {
			stringBuilder.append("\n").append(entryNumber).append(". ");
			stringBuilder.append(entry.getMsg()).append(" \u2014 ").append(entry.getAuthor());
			entryNumber++;
		}
		return stringBuilder.toString();
	}

	private static String getChangeLogFromFile(String file) {
		try {
			BufferedReader reader = new BufferedReader( new FileReader (file));
			String line = null;
			StringBuilder  stringBuilder = new StringBuilder();
			String ls = System.getProperty("line.separator");

			while( ( line = reader.readLine() ) != null ) {
				stringBuilder.append( line );
				stringBuilder.append( ls );
			}

			return stringBuilder.toString();

		} catch (IOException e) {
			return null;
		}
	}

	public static String downloadFromUrl(String urlString, PrintStream logger) throws IOException {

		logger.println("downloadFromUrl: " + urlString);
		InputStream is = null;
		FileOutputStream fos = null;
		long timeStamp = System.currentTimeMillis();

		URL url = new URL(urlString);

		File tempFile = File.createTempFile("instrumented-" + timeStamp, ".apk");

		try {
			URLConnection urlConn = url.openConnection();//connect

			is = urlConn.getInputStream();               //get connection inputstream
			fos = new FileOutputStream(tempFile);   //open outputstream to local file

			byte[] buffer = new byte[4096];              //declare 4KB buffer
			int len;

			//while we have availble data, continue downloading and storing to local file
			while ((len = is.read(buffer)) > 0) {
				fos.write(buffer, 0, len);
			}
		} finally {
			try {
				if (is != null) {
					is.close();
				}
			} finally {
				if (fos != null) {
					fos.close();
				}
			}
		}

		return tempFile.getPath();
	}

	public static String getApkFilePath(String appFile, TestFairyAndroidRecorder.AndroidBuildEnvironment testFairyEnvironment, EnvVars vars) throws TestFairyException {
		if (appFile == null || appFile.length() == 0) {
			throw new TestFairyException("Can't find a APK " + appFile);
		}
		String toReturn = vars.expand(appFile);
		if(Validation.isValidAPK(testFairyEnvironment.jarsignerPath, toReturn)) {
			return toReturn;
		} else {
			throw new TestFairyException("Can't validate your apk, the following command failed: " + testFairyEnvironment.jarsignerPath + " -verify " + toReturn);
		}
	}

	public static String getFilePath(String file, String name, EnvVars vars, Boolean required) throws TestFairyException {
		if (file == null || file.length() == 0){
			if (required) {
				throw new TestFairyException("Can't find a " + name + " in " + file);
			} else {
				return null;
			}
		}
		String toReturn = vars.expand(file);
		if(isFileExists(toReturn)) {
			return toReturn;
		} else if (required) {
			throw new TestFairyException("Can't find a " + name + " in " + toReturn + " the original path was " + file);
		} else {
			return null;
		}
	}

	private static boolean isFileExists(String file) {
		File f = new File(file);
		return f.exists();
	}

	public static String createEmptyFile() throws IOException {
		long timeStamp = System.currentTimeMillis();
		File tempFile = File.createTempFile("ttemp-" + timeStamp, ".apk");
		return tempFile.getPath();
	}

	public static void setJenkinsUrl(EnvVars vars) {

		String hudsonUrl = vars.expand("$HUDSON_URL");
		if (hudsonUrl != null && !hudsonUrl.isEmpty() && !hudsonUrl.equals("$HUDSON_URL")) {
			Uploader.JENKINS_URL = hudsonUrl;
		}
	}

	public static String getVersion(Class c) {
		return c.getPackage().getImplementationVersion();
	}
}