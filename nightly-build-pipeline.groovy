/*
 Pipeline that will build a Intellego binary, install on servers and run tests
*/

currentBuild.displayName = INTELLEGO_VERSION + ' Build. SRC:' + INTELLEGO_CODE_BRANCH + ' REST:' + RESTAPI_BRANCH + ' No:' + env.BUILD_NUMBER

def TESTS_FAILED = '0'
def FAILED_LOGS_DIR = '/tmp/rest-api-logs-nightly-' + RESTAPI_BRANCH
def ALL_LOGS_DIR = '/tmp/all-rest-api-logs-nightly-' + RESTAPI_BRANCH

//def TESTNG_LOGS =  '/tmp/testng-logs-nightly-' + RESTAPI_BRANCH

	//Create a temp directory for REST API results
	node('jenkins-slave') {
		sh (script: "rm -rf ${FAILED_LOGS_DIR} ${ALL_LOGS_DIR}", returnStdout: true)
		sh (script: "mkdir -p ${FAILED_LOGS_DIR} ${ALL_LOGS_DIR}", returnStdout: true)
		
	}

	
//////////////////
//				//
//    START 	//
//				//
//////////////////


timestamps {

stage ('Build Intellego binary'){

	/*
	// "Send an email"
	node ('master') {
		mail (to: MAILING_LIST,
		subject: "Job ${env.JOB_NAME} is running",
		body: 'Parameters - Code: ' + INTELLEGO_CODE_BRANCH + ' RESTAPI: ' + RESTAPI_BRANCH );
	}
	*/

	// check if ISO needs to be upgraded
	if ( ISO_UPGRADE ) {
		node ('master') {
			build job: 'upgrade-intellego-vms', parameters: [[$class: 'StringParameterValue', name: 'HOSTS', value: '10.0.158.131, 10.0.158.132, 10.0.158.134, 10.0.158.147, 10.0.158.148, 10.0.158.151, 10.0.158.152, 10.0.158.161'], [$class: 'StringParameterValue', name: 'VERSION', value: ISO_UPGRADE ], [$class: 'StringParameterValue', name: 'PRODUCT', value: 'intellego']]
		}
	}

	if ( ONLY_RUN_TESTS == 'false' ) {

		// If prebuilt-binary is supplied, don't build from scratch
		
		if ( PREBUILT_BINARY_PATH ) {
			echo " ******* Skipping binary building steps ********"
			def DIR = PREBUILT_BINARY_PATH
			node ('intellego-build-machine') {
				try{
					ws("${DIR}"){
						archive '*.bin'
					}
				}
				catch(err){
					emailext body: 'BUILD_URL = ' + env.BUILD_URL + '/consoleFull', subject: 'Could not find PREBUILT binary! ', to: MAILING_LIST
					throw err
				}
			}
		} 
		else {
			echo "*********************************************"
			echo " ******** Building Intellego Binary *********"
			echo "*********************************************"
			
			node ('intellego-build-machine') {
				
				ws('/home/support/intellego') {
					echo "Checking out code..."
					git url: 'ssh://git@10.0.135.6/intellego.git', branch: INTELLEGO_CODE_BRANCH
				}
				
				// Cleanup old binary
				sh 'sudo rm -rf /home/support/bin/REL_' + INTELLEGO_VERSION + '/root/*'

				// Generate name for the new BINARY based on timestamp
				BINARY = INTELLEGO_VERSION + '.' + INTELLEGO_CODE_BRANCH + '.' + env.BUILD_TIMESTAMP
                
                try {
					ws('/home/support/intellego/build_tool') {
						sh 'sudo ./build-intellego.sh ' + BINARY 
					}
                }
                catch(err){
					// Email in case the build failed
					emailext body: 'BUILD_URL = ' + env.BUILD_URL + '/consoleFull', subject: 'Nightly coded pipeline: Binary build failed! ', to: MAILING_LIST
					throw err
				}
				
				echo "Archiving the binary..."
				def DIR = '/home/support/bin/REL_' + INTELLEGO_VERSION + '/root/' + BINARY
				ws("${DIR}") {
					archive '*.bin'
				}
			}// node
		} // end of else block
	} // end of ONLY_RUN_TESTS
} // End of stage

stage ('Deploy'){

	if ( ONLY_RUN_TESTS == 'false' ) {
	
		parallel 'Node 131': {
			
				deploy('10.0.158.131')
			
		}, 
		'Node 132': {
			
				deploy('10.0.158.132')
			
		}, 
		'Node 134': {
			
				deploy('10.0.158.134')
			
		}, 
		/*
		'Node 147': {
			
				deploy('10.0.158.147')
			
		}, 
		*/
		'Node 148': {
			
				deploy('10.0.158.148')
			
		}, 
		'Node 151': {
			
				deploy('10.0.158.151')
			
		}, 
		/*
		'Node 161': {
			
				deploy('10.0.158.161')
			
		}, 
		*/
		'Node 152': {
			
				deploy('10.0.158.152')
			
		} 
	} // end of ONLY_RUN_TESTS block
}//end of stage

stage ('Install and Test') {

	parallel '134-148': {
		timeout(time:5, unit:'HOURS') {
			if ( ONLY_RUN_TESTS == 'false' ) {
		
				// Ist IP is VMC, second is full install
				install('10.0.158.134', '10.0.158.148', INTELLEGO_CODE_BRANCH) 
		    
			} // end of if ONLY_RUN_TESTS

			//REST API Tests
			node('jenkins-slave') {
				deleteDir()
				// Checkout the rest-api code
				git url: 'git@bitbucket.org:ss8/intellego-rest-api.git', branch: RESTAPI_BRANCH

				// Capture all details in Summary.HTML before running all tests
				//sh 'echo "<h3>Nightly build pipeline has completed!</h3>" > ' + FAILED_LOGS_DIR + '/Summary.HTML'
				sh 'echo "<b>Build Details:</b>" > ' + FAILED_LOGS_DIR + '/Summary.HTML'
				sh 'echo "<p>Intellego Source Branch - " ' + INTELLEGO_CODE_BRANCH + ' >> ' + FAILED_LOGS_DIR + '/Summary.HTML'
				sh 'echo "<p>Rest API Branch - " ' + RESTAPI_BRANCH + ' >> ' + FAILED_LOGS_DIR + '/Summary.HTML'
				sh 'echo "<p>Console Output - <a href= "' + env.BUILD_URL + '"consoleFull>"' + env.BUILD_URL + '"consoleFull</a>"' + ' >> ' + FAILED_LOGS_DIR + '/Summary.HTML'
				//sh 'echo INTELLEGO_BINARY: ' + BINARY + ' >> ' + FAILED_LOGS_DIR  + '/Summary.HTML'
				sh 'echo "<p>" >> ' + FAILED_LOGS_DIR + '/Summary.HTML'
				sh 'echo "<b>Rest API Test Results:</b>" >> ' + FAILED_LOGS_DIR + '/Summary.HTML'


				if ( run_level1 == 'true' ) {
					run_suite ( 'level1_tests', 'qa-at-158-148.yaml' )
				}
				
				if ( run_rtf == 'true' ) {
					run_suite ( 'rtf_all', 'qa-at-158-151.yaml' )
				}

				if ( run_regression == 'true' ) {
					run_suite ( 'regression_tests', 'qa-at-158-151.yaml' )
				}
			
				if ( run_level2== 'true' ) {
					run_suite ( 'level2_tests', 'qa-at-158-151.yaml' )
				}
				

			} // End of node jenkins-slave block
		} //timeout
	}, // end of 134-148 block

	'151-152': {
		timeout(time:5, unit:'HOURS') {
			// Install Intellego DPE on Node 152'
			if ( ONLY_RUN_TESTS == 'false' ) {
			
				// Ist IP is VMC, second is full install
				install('10.0.158.152', '10.0.158.151', INTELLEGO_CODE_BRANCH) 
			
			} // end of ONLY_RUN_TESTS block

			//REST API Tests'
			node('jenkins-slave') {
				deleteDir()
				git url: 'git@bitbucket.org:ss8/intellego-rest-api.git', branch: RESTAPI_BRANCH

				if ( run_level3 == 'true' ) {
					run_suite ( 'level3_tests', 'qa-at-158-151.yaml' )
				}

				if ( run_alltests == 'true' ) {
					run_suite ( 'all_tests', 'qa-at-158-151.yaml' )
				}

				if ( run_kddi== 'true' ) {
					run_suite ( 'kddi_tests', 'qa-at-158-151.yaml' )
				}

			}//node
		}
	}, //151-152

	'131-132': {
		timeout(time:5, unit:'HOURS') {
			if ( ONLY_RUN_TESTS == 'false' ) {
				// Ist IP is VMC, second is full install
				install('10.0.158.132', '10.0.158.131', INTELLEGO_CODE_BRANCH) 			
			} // end of ONLY_RUN_TESTS block

			//REST API Reporting + DataWipe + IO Workflow'
			node('jenkins-slave') {
				deleteDir()
				git url: 'git@bitbucket.org:ss8/intellego-rest-api.git', branch: RESTAPI_BRANCH

				if ( run_reporting == 'true' ) {
					run_suite ( 'reporting_tests', 'qa-at-158-131.yaml' )
				}

				if ( run_datawipe == 'true' ) {
					run_suite ( 'datawipe_tests', 'qa-at-158-131.yaml' )
				}

				if ( run_io == 'true' ) {
					run_suite ( 'io_tests', 'qa-at-158-131.yaml' )
				}

				if ( run_telephony == 'true' ) {
					run_suite ( 'telephony_tests', 'qa-at-158-131.yaml' )
				}
			}//node
		}
	}, // 131-132

	'147-161': {
	/*
		if ( ONLY_RUN_TESTS == 'false' ) {
			timeout(time:2, unit:'HOURS') {
				try{
					//'Install Intellego DPE on Node 147'
					node('10.0.158.147') {
						sh NTPDATE
						sh 'sudo -u root -i /home/support/install.sh -b ' + INTELLEGO_CODE_BRANCH
					} //end of node

					// Install Intellego on Node 161'
					node('10.0.158.161') {
						sh NTPDATE
						sh 'sudo -u root -i /home/support/install.sh -b ' + INTELLEGO_CODE_BRANCH
						sh COPY_DATAWIPE_CONF
						sh INTELLEGOOAMP_START
						sh CHECKPORTS
					} //end of node

					//Restart vmc on 147'
					node('10.0.158.147') {
						sh CHECKVMC
					} //node
				}
				catch(err){
					emailext attachLog: 'true', subject: 'Installing Intellego: Nightly build failed at 147-161 step', to: MAILING_LIST
					throw err
				}
			} //end of timeout block
		} // End of RUN_ONLY_TESTS block

		//REST API v2 regression and Telephony'
		node('jenkins-slave') {
			deleteDir()
			git url: 'git@bitbucket.org:ss8/intellego-rest-api.git', branch: RESTAPI_BRANCH

			// keep this not running any test so Rajesh can use this pair to do his tests

		} //node
	*/
	} // End of 147-161 and parallel block
} // end of stage

stage ('Reporting') {

	node('jenkins-slave'){
  
		ws ("${FAILED_LOGS_DIR}") {

			echo "**************** Sending logs in an email ********************"
			
			try{
				emailext attachmentsPattern: '*.log, *.html', mimeType: 'text/html', body: '${FILE,path="' + FAILED_LOGS_DIR + '/Summary.HTML"}', subject: 'END Intellego Coded Pipeline Build SOURCE - ' + INTELLEGO_CODE_BRANCH + ' RESTAPI - ' + RESTAPI_BRANCH, to: MAILING_LIST
				
				sh (script: "zip -j Failed_Tests_logs.zip ${FAILED_LOGS_DIR}/*", returnStdout: true)
				sh (script: "zip -j All_Tests_logs.zip ${ALL_LOGS_DIR}/*", returnStdout: true)
				archive 'All_Tests_logs.zip, Failed_Tests_logs.zip, Summary.HTML'
			
			}
			catch(err){
				currentBuild.result = 'SUCCESS'
			}
		} 
		
	} 
	
} 

} // End of timestamp block


/////////////////////
//
//   Methods go here
//
/////////////////////

def deploy(IP) {

	def COPY_BINARY='sudo rm -f /SS8/SS8_Intellego.bin; sudo mv *.bin /SS8/SS8_Intellego.bin; sudo chmod 775 /SS8/SS8_Intellego.bin'
	node(IP){
		def exists = fileExists '*.bin'
		if (exists) {
			sh 'sudo rm -f *.bin'
		}
		unarchive mapping: ['*.bin' : '.']
		sh COPY_BINARY
	}
}

def install(VMC, FULL_INTELLEGO, INTELLEGO_CODE_BRANCH) {

	def NTPDATE = 'sudo -u root -i service ntpd stop; sudo -u root -i ntpdate 10.0.158.153; sudo -u root -i service ntpd start'
	def COPY_DATAWIPE_CONF = 'sudo -u root -i /home/support/copy-datawipe-conf.sh'
	def INTELLEGO_RESTART = 'sudo -u root -i /etc/init.d/intellego restart'
	//def INTELLEGOOAMP_START = 'sudo -u root -i /etc/init.d/intellegooamp start'
	def INTELLEGOOAMP_START = 'sudo -u root -i /opt/intellego/Base/bin/intellegooamp-super start'
	//def CHECKPORTS = 'sudo -u root -i /home/support/checkPorts.sh'
	def CHECKVMC = 'sudo -u root -i /home/support/checkVMC.sh'

    
	    try {
			node(VMC) {
				sh NTPDATE
				sh 'sudo -u root -i /home/support/install.sh -b ' + INTELLEGO_CODE_BRANCH
			}
					
			node(FULL_INTELLEGO) {
				sh NTPDATE
				sh 'sudo -u root -i /home/support/install.sh -b ' + INTELLEGO_CODE_BRANCH
				sh COPY_DATAWIPE_CONF
				sh INTELLEGO_RESTART
				sh INTELLEGOOAMP_START
				//sh CHECKPORTS
			}
					
			node(VMC) {
				sh CHECKVMC
			}
		}
		catch(err){
			emailext attachLog: 'true', subject: 'Installing Intellego: Nightly build failed', to: MAILING_LIST
			throw err
		}

}

def run_suite(suite, env) {

	def FAILED_LOGS_DIR = '/tmp/rest-api-logs-nightly-' + RESTAPI_BRANCH
	def ALL_LOGS_DIR = '/tmp/all-rest-api-logs-nightly-' + RESTAPI_BRANCH
	//def TESTNG_LOGS =  '/tmp/testng-logs-nightly-' + RESTAPI_BRANCH
	
    //DIR = pwd()

	
	try {
	    sh 'echo "********** Started running Test suite ' + suite + ' ****************"'
		sh (script: "./gradlew -Dreporting=${REPORTING} -DbuildLogUrl=BUILD_URL/console \
				-DpipelineName=Intellego-CI-Coded-Pipeline -Dsuite=resources/suites/${suite}.xml \
				-Denv=resources/config/${env} run > ${ALL_LOGS_DIR}/${suite}.log 2>&1 || true ; cp build/reports/tests/emailable-report.html \
				build/reports/tests/${suite}.html", returnStdout:true)
		
		// Create the contents for Summary file
        sh 'echo "<br>" >> ' + FAILED_LOGS_DIR + '/Summary.HTML'
        sh 'echo "--------------" >> ' + FAILED_LOGS_DIR + '/Summary.HTML'
        sh 'echo "<br>" >> ' + FAILED_LOGS_DIR + '/Summary.HTML'
        sh 'echo ' + suite + ' >> ' + FAILED_LOGS_DIR + '/Summary.HTML'
        sh 'echo "<br>" >> ' + FAILED_LOGS_DIR + '/Summary.HTML'
        sh 'echo "--------------" >> ' + FAILED_LOGS_DIR + '/Summary.HTML'
        sh 'echo "<br>" >> ' + FAILED_LOGS_DIR + '/Summary.HTML'
		
        sh 'grep failed build/reports/tests/testng-results.xml | head -1 | sed -e "s/<//" -e "s/>//" -e "s/testng-results //" >> ' + FAILED_LOGS_DIR + '/Summary.HTML'
        //sh 'grep failed build/reports/tests/testng-results.xml | head -1 | sed -e "s/<//" -e "s/>//" -e "s/testng-results //" -e "s+failed+<b><font color=red>failed</font></b>+" -e "s+passed+<b><font color=green>passed</font></b>+" >> ' + FAILED_LOGS_DIR + '/Summary.HTML'
        //sh ('grep -wq \"SUCCESSFUL\" ' + suite + '.log ; echo $?')
		sh (script: "grep -wq SUCCESSFUL ${ALL_LOGS_DIR}/${suite}.log ; echo \$? ", returnStdout: true)
    } // try block closing
    catch(err) {
        currentBuild.result = 'SUCCESS' // If not set to SUCCESS, the pipeline interprets to an entire failed build
        //TESTS_FAILED++  // increment the count of tests failed
		//sh 'echo No. of tests failed: ' + TESTS_FAILED
		echo "False. Successful not found.  need to copy"
        //sh 'cp ' + suite + '.log build/reports/tests/' + suite +'.html ' + FAILED_LOGS_DIR
		sh (script: "cp ${ALL_LOGS_DIR}/${suite}.log  build/reports/tests/${suite}.html  ${FAILED_LOGS_DIR}", returnStdout: true)
        
		
    } // catch block closing

} // end of def run_suite
