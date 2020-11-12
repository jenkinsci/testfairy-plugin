package org.jenkinsci.plugins.testfairy;

import com.testfairy.uploader.TestFairyException;
import com.testfairy.uploader.Uploader;
import com.testfairy.uploader.Validation;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.Serializable;

import static hudson.Util.getHostName;

public class TestFairyAndroidRecorder extends TestFairyBaseRecorder {

	private String keystorePath;
	private final Secret storepass;
	private final Secret alias;
	private final Secret keypass;

	@DataBoundConstructor
	public TestFairyAndroidRecorder(Secret apiKey, String appFile, String mappingFile,
					String testersGroups, Boolean notifyTesters, Boolean autoUpdate,
					String maxDuration, Boolean recordOnBackground, Boolean dataOnlyWifi,
					Boolean isVideoEnabled, String screenshotInterval, String videoQuality, String advancedOptions,
					String keystorePath, Secret storepass, Secret alias, Secret keypass,
					Boolean cpu, Boolean memory, Boolean network,
					Boolean logs, Boolean phoneSignal, Boolean wifi,
					Boolean gps, Boolean battery, Boolean openGl
	) {

		super(apiKey, appFile, mappingFile, testersGroups, notifyTesters, autoUpdate, maxDuration,
		    recordOnBackground, dataOnlyWifi, isVideoEnabled, screenshotInterval, videoQuality, advancedOptions, cpu, memory, logs, network, phoneSignal, wifi, gps, battery, openGl);

		this.keystorePath = keystorePath;
		this.storepass = storepass;
		this.alias = alias;
		this.keypass = keypass;

	}



	public String getKeystorePath() {
		return keystorePath;
	}

	public String getStorepass() {
		return storepass.getPlainText();
	}

	public String getAlias() {
		return alias.getPlainText();
	}

	public String getKeypass() {
		return keypass.getPlainText();
	}

	@Override
	public boolean perform(AbstractBuild build, Launcher launcher, final BuildListener listener) throws InterruptedException, IOException {

		if (build.getResult() != null && build.getResult() == Result.FAILURE) {
			return false;
		}
		listener.getLogger().println("TestFairy Uploader (Deprecated)... v " + Utils.getVersion(getClass()) + ", run on " + getHostName());
		try {
			EnvVars vars = build.getEnvironment(listener);
			String changeLog = Utils.extractChangeLog(build, vars, build.getChangeSet(), listener.getLogger());
			AndroidBuildEnvironment environment = getDescriptor().getEnvironment(launcher);

			try {
				launcher.getChannel().call(new AndroidRemoteRecorder(listener, this, vars, environment, changeLog));

			} catch (Throwable ue) {
				throw new TestFairyException(ue.getMessage(), ue);
			}
			return true;

		} catch (TestFairyException e) {
			listener.error(e.getMessage() + "\n");
			e.printStackTrace(listener.getLogger());
			return false;
		}
	}

	class AndroidRemoteRecorder extends RemoteRecorder {
		private final AndroidBuildEnvironment environment;

		public AndroidRemoteRecorder(BuildListener listener, TestFairyAndroidRecorder androidRecorder, EnvVars vars, AndroidBuildEnvironment environment, String changeLog) {
			super(listener, androidRecorder, vars, changeLog);
			this.environment = environment;
		}

		@Override
		public String call() throws Throwable {

			Uploader uploader = new Uploader(listener.getLogger(), Utils.getVersion(getClass()));

			Utils.setJenkinsUrl(vars);
			Uploader.setServer(vars, listener.getLogger());
			String appFilePath = Utils.getApkFilePath(appFile, environment, vars);
			String mappingFilePath = Utils.getFilePath(mappingFile, "symbols file", vars, false);

			checkKeystoreParams(vars);

			// the last parameter is false, it means that "notify" and "auto-update" will NOT be sent (that will be sent in uploadSignApk()).
			String responseString = uploader.uploadApp(appFilePath, mappingFilePath, changeLog, recorder, false);
			JSONObject response = JSONObject.fromObject(responseString);

			String instrumentedUrl = response.getString("instrumented_url");
			instrumentedUrl += instrumentedUrl + "?api_key=" + apiKey;
			String instrumentedAppPath = Utils.downloadFromUrl(instrumentedUrl, listener.getLogger());

			String signedFilePath = uploader.signingApk(environment, instrumentedAppPath, (TestFairyAndroidRecorder)recorder);

			String responseSigned = uploader.uploadSignedApk(signedFilePath, mappingFilePath, recorder);

			//print the build url
//			listener.getLogger().println("Check the new build: " + responseSigned.getString("build_url"));
			return responseSigned;
		}
	};

	private void checkKeystoreParams(EnvVars vars) throws TestFairyException {

		keystorePath = Utils.getFilePath(keystorePath, "keystore file" ,vars, true);

		if (getKeystorePath() == null || getKeystorePath().isEmpty()) {
			throw new TestFairyException("Missing Keystore file");
		}
		if (getStorepass() == null || getStorepass().isEmpty()) {
			throw new TestFairyException("Missing Storepass");
		}
		if (getAlias() == null || getAlias().isEmpty()) {
			throw new TestFairyException("Missing Alias");
		}
	}

	@Override
	public DescriptorImpl getDescriptor() {
		return (DescriptorImpl) super.getDescriptor();
	}


	/**
	 * Descriptor for {@link TestFairyAndroidRecorder}. Used as a singleton.
	 * The class is marked as public so that it can be accessed from views.
	 * See <tt>src/main/resources/hudson/plugins/hello_world/TestFairyRecorder/*.jelly</tt>
	 * for the actual HTML fragment for the configuration screen.
	 */
	@Extension // This indicates to Jenkins that this is an implementation of an extension point.
	public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> implements Serializable {
		/**
		 * To persist global configuration information,
		 * simply store it in a field and call save().
		 * If you don't want fields to be persisted, use <tt>transient</tt>.
		 */
		private String jarsignerPath;
		private String zipalignPath;

		/**
		 * In order to load the persisted global configuration, you have to
		 * call load() in the constructor.
		 */
		public DescriptorImpl() {
			load();
		}


		/**
		 * /**
		 * Performs on-the-fly validation of the form field 'name'.
		 *
		 * @param value This parameter receives the value that the user has typed.
		 * @return Indicates the outcome of the validation. This is sent to the browser.
		 * Note that returning {@link FormValidation#error(String)} does not
		 * prevent the form from being saved. It just means that a message
		 * will be displayed to the user.
		 * @return return
		 * @throws IOException
		 * @throws ServletException
		 */
		public FormValidation doCheckApiKey(@QueryParameter String value) throws IOException, ServletException {
			if (value.length() == 0) {
				return FormValidation.error("Please set an ApiKey");
			}
			if (value.length() != 40) {
				return FormValidation.warning("This is invalid ApiKey");
			}
			return FormValidation.ok();
		}

		public FormValidation doCheckAppFile(@QueryParameter String value) throws IOException, ServletException {
			return Validation.checkAbsolutePath(value);
		}

		public FormValidation doCheckMappingFile(@QueryParameter String value) throws IOException, ServletException {
			return Validation.checkAbsolutePath(value);
		}

		public FormValidation doCheckZipPath(@QueryParameter String value) throws IOException, ServletException {
			return Validation.checkProgram(value);
		}
		public FormValidation doCheckJarsignerPath(@QueryParameter String value) throws IOException, ServletException {
			return Validation.checkProgram(value);
		}
		public FormValidation doCheckZipalignPath(@QueryParameter String value) throws IOException, ServletException {
			return Validation.checkProgram(value);
		}


		/**
		 * Called when rendering maxDuration field
		 *
		 * @return ListBoxModel
		 */
		public ListBoxModel doFillMaxDurationItems() {
			ListBoxModel items = new ListBoxModel();

			items.add("10 minutes", "10m");
			items.add("1 hour", "60m");
			items.add("5 hours", "300m");
			items.add("24 hours", "1440m");
			return items;
		}

		public ListBoxModel doFillScreenshotIntervalItems() {
			ListBoxModel items = new ListBoxModel();

			items.add("1 second", "1");
			items.add("2 seconds", "2");
			items.add("5 seconds", "5");
			return items;
		}

		public ListBoxModel doFillVideoQualityItems() {
			ListBoxModel items = new ListBoxModel();


			items.add("High", "high");
			items.add("Medium", "medium");
			items.add("Low", "low");

			return items;
		}

		public boolean isApplicable(Class<? extends AbstractProject> aClass) {
			// Indicates that this builder can be used with all kinds of project types
			return true;
		}

		/**
		 * This human readable name is used in the configuration screen.
		 */
		public String getDisplayName() {
			return " TestFairy Uploader (Deprecated)";
		}

		@Override
		public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {

			jarsignerPath = formData.getString("jarsignerPath");
			zipalignPath = formData.getString("zipalignPath");

			save();
			return super.configure(req, formData);
		}



		public String getJarsignerPath() {
			return jarsignerPath;
		}

		public String getZipalignPath() {
			return zipalignPath;
		}

		public AndroidBuildEnvironment getEnvironment(Launcher launcher) throws TestFairyException {

//			//the default variables are only for display
			if (jarsignerPath == null) {
				jarsignerPath = "jarsigner";
			}
			if (zipalignPath == null) {
				zipalignPath = "zipalign";
			}

			try {
				launcher.getChannel().call(new RemoteRecorder() {
					@Override
					public String call() throws Throwable {
						Validation.isValidProgram(jarsignerPath, "jarsigner");
						Validation.isValidProgram(zipalignPath, "zipalign");
						return null;
					}
				});

			} catch (Throwable ue) {
				throw new TestFairyException(ue.getMessage(), ue);
			}
			return new AndroidBuildEnvironment(jarsignerPath, zipalignPath);
		}
	}

	public static class AndroidBuildEnvironment implements Serializable{
		public String zipPath;
		public String jarsignerPath;
		public String zipalignPath;

		/**
		 * @param jarsignerPath jarsignerPath
		 * @param zipalignPath zipalignPath
		 */
		public AndroidBuildEnvironment(String jarsignerPath, String zipalignPath) {

			this.jarsignerPath = jarsignerPath;
			this.zipalignPath = zipalignPath;
		}
	}
}

