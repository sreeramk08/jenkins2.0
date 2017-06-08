/*
                FLEXIBLE pipeline code will build a Intellego binary, install on servers and run tests
*/

//	Set the name of the Jenkins build
currentBuild.displayName = 'No:' + env.BUILD_NUMBER + ' VER:' +INTELLEGO_VERSION + ' SRC:' + INTELLEGO_CODE_BRANCH + ' REST:' + RESTAPI_BRANCH 

def IP_ADDRESSES = ""
def TEST_SUITES = ""
def ALL_LOGS_DIR = ""
def FAILED_LOGS_DIR = ""


//	Start 
try {
	timestamps {
		node ('jenkins-slave') {
			// 	Create temp directories for storing the logs. Support for builds running in parallel as well by adding the build number at the end. 
				FAILED_LOGS_DIR =  '/var/www/html/ci-logs/failed-rest-api-flexible-' + RESTAPI_BRANCH  + '-' + env.BUILD_NUMBER
				ALL_LOGS_DIR    =  '/var/www/html/ci-logs/all-rest-api-flexible-'    + RESTAPI_BRANCH  + '-' + env.BUILD_NUMBER
		
			//	Cleanup and recreate 
				sh (script: "rm -rf ${FAILED_LOGS_DIR} ${ALL_LOGS_DIR}", returnStdout: false )
				sh (script: "mkdir -p ${FAILED_LOGS_DIR} ${ALL_LOGS_DIR}", returnStdout: false)

			// 	Test suites are determined by looking for env variables of the format <suite>_tests=true. Ex: level1_tests=true 
				TEST_SUITES =	sh (script: "env | grep 'tests=true' | \
								sed 's/=true//g' | \
								sed -e 'H;\${x;s/\\n/,/g;s/^,//;p;};d'", returnStdout: true).trim()
				
			//	We want to make sure no. of pairs chosen is not more than suites. No point in reserving a pair without a suite to run
				NO_OF_TEST_SUITES = sh (script: "env | grep 'tests=true' | sed 's/=true//g' | wc -l", returnStdout:true).trim()
				
				if ( PAIRS_TO_USE > NO_OF_TEST_SUITES ){
					PAIRS_TO_USE  =  NO_OF_TEST_SUITES
				}
				
				echo "INFO: Got TEST_SUITES as " + TEST_SUITES
			
			//	Get the IP addresses to work with. Master list resides (only) on jenkins-slave-1.  
				node('jenkins-slave-1'){
					try{
						IP_ADDRESSES = sh (script: "/home/support/jenkins/dish_out_ci_machines.sh -g ${PAIRS_TO_USE}", returnStdout: true)
					}
					catch(err) {
						emailext subject: 'FAILED No:' + env.BUILD_NUMBER + ' Intellego CI Pipeline. No free IPs left!', to: MAILING_LIST
						currentBuild.result = 'FAILURE'
						throw err
					}
					
				echo "INFO: Got IPS: " + IP_ADDRESSES
			}
			
			//	Start creating the Summary for emailing
				prepare_summary(ALL_LOGS_DIR, INTELLEGO_CODE_BRANCH, RESTAPI_BRANCH, IP_ADDRESSES, TEST_SUITES)
			
			// 	Send starting email
				send_starting_email(MAILING_LIST, IP_ADDRESSES, TEST_SUITES)
		
			// 	If running only tests skip building binary
				if ( ONLY_RUN_TESTS == 'false' ) {

				// 	If prebuilt-binary is supplied, don't build from scratch
					if ( PREBUILT_BINARY_PATH ) {
					    pre_built_binary(PREBUILT_BINARY_PATH, ALL_LOGS_DIR)
					} 
					else {
						build_intellego_binary(INTELLEGO_CODE_BRANCH, INTELLEGO_VERSION, MAILING_LIST, ALL_LOGS_DIR)
					} 
				} 
				
			// 	Create the test environments based on the IP addresses supplied
				create_env(IP_ADDRESSES)
			
			// 	Generate the parallel steps 
				sh 'python intellego/parallelism.py -i "' + IP_ADDRESSES + '" -t "' + TEST_SUITES + '" | sed "s/\\(.*\\)::/\\1/" > /tmp/parallel-steps'
				sets = readFile('/tmp/parallel-steps')
				SETS = sets.tokenize("::")
				//	echo "Got the list of parallel steps as: " + SETS
    
			// 	Initialize the map we'll store the parallel steps in for execution
				def stepsForParallel = [:]

			// 	Loop that gets the parallel steps based on what we have so far
				for (int i = 0; i < SETS.size(); i++) {
					//	Get the actual string here.
					def step = SETS.get(i)
					//	echo "Parallel steps: " + step
					def stepName = "${step}"
        
					stepsForParallel[stepName] = parallelstep(step, ALL_LOGS_DIR, FAILED_LOGS_DIR)
				}
			
			//	Spawn the parallel blocks	
				parallel stepsForParallel
			
			// 	Process the rest api results once all done
				process_restapi_results(ALL_LOGS_DIR, FAILED_LOGS_DIR)
		
			//	All Done! Send final email. 
				send_final_email(ALL_LOGS_DIR, FAILED_LOGS_DIR)
			
			//	Give back the IPs for next CI
				give_back_ips(IP_ADDRESSES)

		} // End of "node" block
	} // End of "timestamp" block
} // End of "try" block

catch (err) {

	node('jenkins-slave') {
		// 	Catch any error not caught by individual try-catch blocks
			emailext body: 'BUILD_URL = ' + env.BUILD_URL + '/consoleFull', subject: 'FAILED No:' + env.BUILD_NUMBER + ' Intellego CI Pipeline failure. ', to: MAILING_LIST
		//	Also release the IP's because build failed
			give_back_ips(IP_ADDRESSES)
			write_to_summary("\\<br\\><b>BUILD FAILED OR ABORTED!</b>", ALL_LOGS_DIR)
			currentBuild.result = 'FAILURE'
	}
}


// 	Create the parallel steps
	def parallelstep(inputString, ALL_LOGS_DIR, FAILED_LOGS_DIR) {
		return {
			node ('jenkins-slave') {
				 
				stage(inputString) {	
					
					def IS = " "
					// 	convert string to list
					IS = inputString.tokenize(',') 
					echo "Got IS as: " + IS
					// 	The last element is the list of IP addresses
					IPS = IS[IS.size() - 1] 
					
					// 	Get the IPs for Intellego and VMC here
					INTELLEGO_IP = IPS.tokenize(' ')[1] 
					echo "Intellego IP is: " + INTELLEGO_IP
					VMC_IP = IPS.tokenize(' ')[2]
					echo "VMC IP is: " + VMC_IP
					
					// 	If we are only running tests no need to build or deploy
					if ( ONLY_RUN_TESTS == 'false' ) {
						//  Deploy + install
					    deploy(IS, INTELLEGO_IP, VMC_IP, ALL_LOGS_DIR)  		
					}
					else {
						// 	Proceed to testing
						run_suites(IS, ALL_LOGS_DIR, "/tmp/${INTELLEGO_IP}-${VMC_IP}.yaml")
					}
				}	
			}	
		} 	
	}

//	Prepare the summary html file that will be emailed out at the very end
	def prepare_summary (ALL_LOGS_DIR, INTELLEGO_CODE_BRANCH, RESTAPI_BRANCH, IP_ADDRESSES, TEST_SUITES){
		sh 'echo "<html>"  > ' + ALL_LOGS_DIR + '/Summary.HTML'                                                
		sh 'echo "<head>" >> ' + ALL_LOGS_DIR + '/Summary.HTML'    
		sh 'echo "<META HTTP-EQUIV="refresh" CONTENT="90">" >> ' + ALL_LOGS_DIR + '/Summary.HTML' 
		sh 'echo "<body style=\\"font-family:Verdana; font-size: 9pt;\\">" >> ' + ALL_LOGS_DIR + '/Summary.HTML'
		sh 'echo "</head>" >> ' + ALL_LOGS_DIR + '/Summary.HTML'                                                      
		sh 'echo "<b>Build Details:</b>" >> ' + ALL_LOGS_DIR + '/Summary.HTML'
		sh 'echo "<p>Intellego Source Branch: " ' + INTELLEGO_CODE_BRANCH + ' >> ' + ALL_LOGS_DIR + '/Summary.HTML'
		sh 'echo "<p>Rest API Branch: " ' + RESTAPI_BRANCH + ' >> ' + ALL_LOGS_DIR + '/Summary.HTML'
		sh 'echo "<p>Suites: " ' + TEST_SUITES + ' >> ' + ALL_LOGS_DIR + '/Summary.HTML'
		sh 'echo "<p>IPs in Use: " ' + IP_ADDRESSES + ' >> ' + ALL_LOGS_DIR + '/Summary.HTML'
		sh 'echo "<p>Console Output: <a href= "' + env.BUILD_URL + '"consoleFull>"' + env.BUILD_URL + '"consoleFull</a>"' + ' >> ' + ALL_LOGS_DIR + '/Summary.HTML'
		sh 'echo "<p>Rest API Logs: <a href="http://10.0.156.71/ci-logs/all-rest-api-flexible-"' + RESTAPI_BRANCH + '-' + env.BUILD_NUMBER + '>Logs</a>" >> ' + ALL_LOGS_DIR + '/Summary.HTML'
		sh 'echo "<p><b>Progress Log:</b><br>" >> ' + ALL_LOGS_DIR + '/Summary.HTML'
	}
	
//	Draft and send an email at the very beginning
	def send_starting_email(MAILING_LIST, IP_ADDRESSES, TEST_SUITES){
		node ('jenkins-slave') {
			//	deleteDir()
			git url: 'git@bitbucket.org:ss8/scripts.git', branch: 'master'
			//	Gather the latest CI scripts.  This is to avoid checking out again and again on all the CI vm's
			stash name: "build-scripts", includes: "ci/install.sh, ci/copy-datawipe-conf.sh, ci/checkVMC.sh"
			wrap([$class: 'BuildUser']) {
				//	If a user started the build, this variable is available.  Else the pipeline was started by timer
				def BUILDUSER = 'Scheduler'
				
				if ( env.BUILD_USER ){
					BUILDUSER = env.BUILD_USER
				}
				//	The email file is generated at /tmp/start_email.HTML of the jenkins-slave server chosen
				sh (script: "./intellego/start_email.sh -i \"${IP_ADDRESSES}\" -t \"${TEST_SUITES}\" -u \"${BUILDUSER}\" ", returnStdout: false)
			
				emailext mimeType: 'text/html', body: '${FILE,path="/tmp/start_email.HTML"}', \
					 subject: 'START No:' + env.BUILD_NUMBER + ' Intellego CI Pipeline SRC:' \
				     + INTELLEGO_CODE_BRANCH + ' REST:' + RESTAPI_BRANCH + ' BY:' + BUILDUSER, to: MAILING_LIST
			}
		}
	}

//	Use a pre-built binary if supplied
	def pre_built_binary (PREBUILT_BINARY_PATH, ALL_LOGS_DIR) {
		echo "******* Skipping binary building steps because pre-built binary was supplied *********"
		def DIR = PREBUILT_BINARY_PATH
		stage('Pre-built Binary') {
			write_to_summary("INFO: Processing Pre-Built Binary\\<br\\>", ALL_LOGS_DIR)
			node ('intellego-build-machine') {
				try{
					ws("${DIR}"){
						
						archive '*.bin'
					}
				}
				catch(err){
					emailext subject: 'Could not find PREBUILT binary path: ' + PREBUILT_BINARY_PATH, to: MAILING_LIST
					throw err
					currentBuild.result = 'FAILURE'
				}
			}
		}
	}

//	Build an intellego Binary
	def build_intellego_binary (INTELLEGO_CODE_BRANCH, INTELLEGO_VERSION, MAILING_LIST, ALL_LOGS_DIR){
		stage('Intellego Binary Build'){
			write_to_summary("INFO: Building Intellego Binary\\<br\\>", ALL_LOGS_DIR)
			echo "*********************************************"
			echo " ******** Building Intellego Binary *********"
			echo "*********************************************"
			
			node ('intellego-build-machine') {
				
				ws('/home/support/intellego') {
					echo "Checking out code..."
					git url: 'ssh://git@10.0.135.6/intellego.git', branch: INTELLEGO_CODE_BRANCH
				}
				
				// 	Cleanup old binary
				sh 'sudo rm -rf /home/support/bin/REL_' + INTELLEGO_VERSION + '/root/*'

				// 	Generate name for the new BINARY based on timestamp
				BINARY = INTELLEGO_VERSION + '.' + INTELLEGO_CODE_BRANCH + '.' + env.BUILD_TIMESTAMP
                
				try {
					ws('/home/support/intellego/build_tool') {
						sh 'sudo ./build-intellego.sh ' + BINARY 
						//sh (script: "sudo ./build-intellego.sh ${BINARY} > /tmp/intellego-build.log 2>&1", returnStdout: true)
						//stash name: "intellego-build-log", includes: "/tmp/intellego-build.log"
					}
				}
				catch(err){
					// 	Email in case the build failed
					emailext body: 'BUILD_URL = ' + env.BUILD_URL + '/consoleFull', subject: 'Nightly coded pipeline: Binary build failed! ', to: MAILING_LIST
					throw err
				}
				
				echo "Archiving the binary..."
				def DIR = '/home/support/bin/REL_' + INTELLEGO_VERSION + '/root/' + BINARY
				ws("${DIR}") {
					archive '*.bin'
				}
			}
		}
	}
	
//	Deploy binary to VM's and install
	def deploy(IS, INTELLEGO_IP, VMC_IP, ALL_LOGS_DIR) {
		def NTPDATE = 'sudo -u root -i service ntpd stop; sudo -u root -i ntpdate 10.0.158.153; sudo -u root -i service ntpd start'
		def COPY_BINARY = 'sudo rm -f /SS8/SS8_Intellego.bin; sudo mv SS8*.bin /SS8/SS8_Intellego.bin; sudo chmod 775 /SS8/SS8_Intellego.bin'
		def INTELLEGO_RESTART = 'sudo -u root -i /etc/init.d/intellego restart'
		def INTELLEGOOAMP_START = 'sudo -u root -i /opt/intellego/Base/bin/intellegooamp-super start'
		
		node(VMC_IP){
			write_to_summary("INFO: Deploying to ${VMC_IP}\\<br\\>", ALL_LOGS_DIR)
			//	Remove any other bin files to avoid runnning out of diskspace as well as wrong versions
			def exists = fileExists 'SS8*.bin'
			if (exists) {
				sh 'sudo rm -f SS8*.bin'
			}
			deleteDir() //	To remove any other bin files that might be there
			unarchive mapping: ['*.bin' : '.']
			sh COPY_BINARY
			
			unstash "build-scripts"		// will unstash in a folder called ci
			sh NTPDATE  
			def INSTALL_SCRIPT = sh (script: "readlink -f ./ci/install.sh", returnStdout: true).trim()
				try{
					write_to_summary("INFO: Installing on ${VMC_IP}\\<br\\>", ALL_LOGS_DIR)
					timeout(time:30, unit:'MINUTES'){
						sh (script: "chmod +x ${INSTALL_SCRIPT}; sudo -u root -i ${INSTALL_SCRIPT} -b ${INTELLEGO_CODE_BRANCH} > /dev/null 2>&1", returnStdout: true)
					}
					write_to_summary("SUCCESS:Installed on ${VMC_IP}\\<br\\>", ALL_LOGS_DIR)
				}
				catch(err){
					write_to_summary("FAILED:Installing on ${VMC_IP}\\<br\\>", ALL_LOGS_DIR)
				}
		}
		
		node(INTELLEGO_IP){
			write_to_summary("INFO: Deploying to ${INTELLEGO_IP}\\<br\\>", ALL_LOGS_DIR)
			
			//	Remove any other bin files to avoid runnning out of diskspace as well as wrong versions
			def exists = fileExists 'SS8*.bin'
			if (exists) {
				sh 'sudo rm -f SS8*.bin'
			}
			deleteDir() //	To remove any other bin files that might be there 
			unarchive mapping: ['*.bin' : '.']
			sh COPY_BINARY
			
			sh 'sudo mount -a'
			unstash "build-scripts"
			sh NTPDATE
			def INSTALL_SCRIPT = sh (script: "readlink -f ./ci/install.sh", returnStdout: true).trim()
				try{
					write_to_summary("INFO: Installing on ${INTELLEGO_IP}\\<br\\>", ALL_LOGS_DIR)
					timeout(time:30, unit:'MINUTES') {
						sh (script: "chmod +x ${INSTALL_SCRIPT}; sudo -u root -i ${INSTALL_SCRIPT} -b ${INTELLEGO_CODE_BRANCH} > /dev/null 2>&1 ", returnStdout: true)
					}
					write_to_summary("SUCCESS:   Installed on ${INTELLEGO_IP}\\<br\\>", ALL_LOGS_DIR)
					
				}
				catch(err){
					write_to_summary("FAILED:Installing on ${INTELLEGO_IP}\\<br\\>", ALL_LOGS_DIR)
				}
				
				write_to_summary("SUCCESS:Post Install steps on ${INTELLEGO_IP}\\<br\\>", ALL_LOGS_DIR)
				sh INTELLEGO_RESTART
				sh INTELLEGOOAMP_START
				
				def COPY_DATAWIPE_CONF = sh (script: "readlink -f ./ci/copy-datawipe-conf.sh", returnStdout: true).trim()
				sh (script: "chmod +x ${COPY_DATAWIPE_CONF}; sudo -u root -i ${COPY_DATAWIPE_CONF}", returnStdout: true)
				
				//sh CHECKPORTS
		}
		
		node(VMC_IP) {
			write_to_summary("SUCCESS:Post Install steps on ${VMC_IP}\\<br\\>", ALL_LOGS_DIR)
			def CHECKVMC = sh (script: "readlink -f ./ci/checkVMC.sh", returnStdout: true).trim()
		//	Sometimes at this step the pipeline will get stuck
			timeout(time:10, unit:'MINUTES') {
				sh (script: "chmod +x ${CHECKVMC}; sudo -u root -i ${CHECKVMC} >&2", returnStdout: true)
			}
		}
	
		echo "INFO: Got IS inside deploy as: " + IS	
		// 	Proceed to testing
		run_suites(IS, ALL_LOGS_DIR, "/tmp/${INTELLEGO_IP}-${VMC_IP}.yaml")
	}

//	Create the test environments based on the IP addresses supplied
	def create_env(IP_ADDRESSES){
		git url: 'git@bitbucket.org:ss8/scripts.git', branch: 'master'
		sh (script: "./intellego/create_test_env.sh ${IP_ADDRESSES} ", returnStdout: false)
	}

//	Run the suites
	def run_suites(IS, ALL_LOGS_DIR, ENVFILE) {
		echo "INFO: IS inside run_suites as: " + IS
		deleteDir()
		git url: 'git@bitbucket.org:ss8/intellego-rest-api.git', branch: RESTAPI_BRANCH
		// 	To avoid conflicting with another directory called scripts inside the intellego repo, checkout within a repo called ci_scripts
		dir('ci_scripts'){
			git url: 'git@bitbucket.org:ss8/scripts.git', branch: 'master'
		}
		//timeout(time:3, unit:'HOURS') {
			sh (script: "./ci_scripts/intellego/run_suites.sh -a \"${IS}\" -l ${ALL_LOGS_DIR} -e ${ENVFILE}", returnStdout: false)
		//}
	}

//	Process the rest api results to figure out how many failed, etc.
	def process_restapi_results(ALL_LOGS_DIR, FAILED_LOGS_DIR){
		echo "Processing rest api test results!"
		sh (script: "./intellego/process_restapi_results.sh ${ALL_LOGS_DIR} ${FAILED_LOGS_DIR}", returnStdout: false)
	}

//	Final email
	def send_final_email(ALL_LOGS_DIR, FAILED_LOGS_DIR) {
		node('jenkins-slave'){
			echo " ************* Sending logs in an email ********************"
			try{
				ws ("${FAILED_LOGS_DIR}") {
					wrap([$class: 'BuildUser']) {
						//	Assume build was started by timer
						def BUILDUSER = 'Scheduler'
						//	If a user triggered the build, only then this variable is available
						if ( env.BUILD_USER ){
							BUILDUSER = env.BUILD_USER
						}
						emailext attachmentsPattern: '*.log, *.html', mimeType: 'text/html', body: '${FILE,path="' + ALL_LOGS_DIR + '/Summary.HTML"}', subject: 'END No:' + env.BUILD_NUMBER + ' Intellego CI Pipeline SRC: ' + INTELLEGO_CODE_BRANCH + ' REST:' + RESTAPI_BRANCH + ' BY:' + BUILDUSER, to: MAILING_LIST
					}
				}	
				sh (script: "zip -j Failed_Tests_logs.zip ${FAILED_LOGS_DIR}/*", returnStdout: true)
				sh (script: "zip -j All_Tests_logs.zip ${ALL_LOGS_DIR}/*", returnStdout: true)
				archive 'All_Tests_logs.zip, Failed_Tests_logs.zip, ${ALL_LOGS_DIR}/Summary.HTML'
			
			}
			catch(err){
					currentBuild.result = 'SUCCESS'
			}	 
		} 
	} 

//	Put the IP's back
	def give_back_ips(IP_ADDRESSES){
		node('jenkins-slave-1'){	
			try{
				sh (script: "/home/support/jenkins/dish_out_ci_machines.sh -t \"${IP_ADDRESSES}\" ", returnStdout: true)
				echo "Successfully released the IPs"
			}
			catch(err) {
				emailext subject: 'FAILED No:' + env.BUILD_NUMBER + ' Could not free-up the IPs!', to: MAILING_LIST
				currentBuild.result = 'FAILURE'
				throw err
			}	
		}
	}

	//	Write to summary file
	def write_to_summary(message, ALL_LOGS_DIR ){
		node('jenkins-slave') {
			def date = sh(script:"date '+%D %H:%M:%S'", returnStdout: true).trim()
			sh (script: "echo ${date} ${message} >> ${ALL_LOGS_DIR}/Summary.HTML", returnStdout: true)
		}
	}

