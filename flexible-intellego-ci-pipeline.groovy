/*
   FLEXIBLE pipeline code will build a Intellego binary, install on servers and run tests
*/

currentBuild.displayName = INTELLEGO_VERSION + ' Build. SRC:' + INTELLEGO_CODE_BRANCH + ' REST:' + RESTAPI_BRANCH + ' No:' + env.BUILD_NUMBER

def TESTS_FAILED = '0'




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
		subject: "Job ${env.JOB_NAME} is running!",
		body: 'Parameters - Code: ' + INTELLEGO_CODE_BRANCH + ' RESTAPI: ' + RESTAPI_BRANCH );
	}
    */
	
	// check if ISO needs to be upgraded
	if ( ISO_UPGRADE ) {
		node ('master') {
		//	build job: 'upgrade-intellego-vms', parameters: [[$class: 'StringParameterValue', name: 'HOSTS', value: '10.0.158.131, 10.0.158.132, 10.0.158.134, 10.0.158.147, 10.0.158.148, 10.0.158.151, 10.0.158.152, 10.0.158.161'], [$class: 'StringParameterValue', name: 'VERSION', value: ISO_UPGRADE ], [$class: 'StringParameterValue', name: 'PRODUCT', value: 'intellego']]
		}
	}

	// Check if we are only running tests.  If yes, skip this block
	if ( ONLY_RUN_TESTS == 'false' ) {

		// If prebuilt-binary is supplied, don't build from scratch
		
		if ( PREBUILT_BINARY_PATH ) {
			echo " ******* Skipping binary building steps ********"
			def DIR = PREBUILT_BINARY_PATH
			node ('intellego-build-machine') {
				try{
					ws("${DIR}"){
						//archive '*.bin'
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
					//git url: 'ssh://git@10.0.135.6/intellego.git', branch: INTELLEGO_CODE_BRANCH
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
					//archive '*.bin'
				}
			}// node
		} // end of else block
	} // end of ONLY_RUN_TESTS
} // End of stage



node ('jenkins-slave') {
    //deleteDir()
    //git url: 'git@bitbucket.org:ss8/scripts.git'
	
	
	def FAILED_LOGS_DIR = '/tmp/rest-api-flexible-' + RESTAPI_BRANCH
	def ALL_LOGS_DIR = '/tmp/all-rest-api-flexible-' + RESTAPI_BRANCH

	// Directories to hold the logs
	node('jenkins-slave') {
		sh (script: "rm -rf ${FAILED_LOGS_DIR} ${ALL_LOGS_DIR}", returnStdout: true)
		sh (script: "mkdir -p ${FAILED_LOGS_DIR} ${ALL_LOGS_DIR}", returnStdout: true)
	}
	
	
	// Create the environment files
	create_env(IP_ADDRESSES)
	
    sh 'python intellego/parallelism.py -i "' + IP_ADDRESSES + '" -t "' + TEST_SUITES + '" | sed "s/\\(.*\\)::/\\1/" > /tmp/aa'
    sets = readFile('/tmp/aa')
    //echo "Got the sets as: " + sets
    // Dont remove this calculation.  
    SETS = sets.tokenize("::")
    echo "Got the list of parallel steps as: " + SETS
    
    
    // The map we'll store the parallel steps in before executing them.
	def stepsForParallel = [:]

	for (int i = 0; i < SETS.size(); i++) {
		// Get the actual string here.
		def step = SETS.get(i)
		echo "Parallel steps: " + step
		def stepName = "${step}"
        
		stepsForParallel[stepName] = parallelstep(step, ALL_LOGS_DIR)
	}

	parallel stepsForParallel

} // node



} // End of timestamp block


// ******************************************
//
// ***********   METHODS   ******************
//
// ******************************************

// ************ MAIN METHOD THAT DOES EVERYTHING!! ********************
def parallelstep(inputString, ALL_LOGS_DIR) {
		return {
			node ('jenkins-slave') {
				

				//stage(IPS) { 
				stage(inputString) {	
					//def TESTS = ' '
					
					IS = inputString.tokenize(',') // convert string to list
					echo "Got IS as: " + IS
					IPS = IS[IS.size() - 1] // The last element is the list of IP addresses
					echo "Got IPS as: " + IPS
					
					// Dont change this.  This works!
					INTELLEGO_IP = IPS.tokenize(' ')[1] 
					echo "Got Intellego IP as: " + INTELLEGO_IP
					VMC_IP = IPS.tokenize(' ')[2]
					echo "Got VMC IP as: " + VMC_IP
					
					/*
					if ( ONLY_RUN_TESTS == 'false' ) {	
						//deploy(INTELLEGO_IP)
						//deploy(VMC_IP)
					
						//install(VMC_IP, INTELLEGO_IP, INTELLEGO_CODE_BRANCH)
					}
					*/
					// Proceed to testing
					run_suites(IS, ALL_LOGS_DIR, "/tmp/${INTELLEGO_IP}-${VMC_IP}.yaml")
					
					
					
				} //stage block
			} // node block
		} //return block
}

def create_env(IP_ADDRESSES){
	git url: 'git@bitbucket.org:ss8/scripts.git', branch: 'master'
	sh (script: "./intellego/create_test_env.sh ${IP_ADDRESSES} ", returnStdout: false)
}

def run_suites(IS, ALL_LOGS_DIR, ENVFILE) {
  
	git url: 'git@bitbucket.org:ss8/intellego-rest-api.git', branch: RESTAPI_BRANCH
	dir('scripts'){
		git url: 'git@bitbucket.org:ss8/scripts.git', branch: 'master'
	}
	sh (script: "./scripts/intellego/run_suites.sh -a \"${IS}\" -l ${ALL_LOGS_DIR} -e ${ENVFILE}", returnStdout: true)
}

def deploy(IP) {

	def COPY_BINARY='sudo rm -f /SS8/SS8_Intellego.bin; sudo mv *.bin /SS8/SS8_Intellego.bin; sudo chmod 775 /SS8/SS8_Intellego.bin'
	node(IP){
		echo "************ Installing Intellego on " + IP  + "*****************"
		def exists = fileExists '*.bin'
		if (exists) {
			sh 'sudo rm -f *.bin'
		}
		unarchive mapping: ['*.bin' : '.']
		sh COPY_BINARY
	}
}

def install(VMC_IP, INTELLEGO_IP, INTELLEGO_CODE_BRANCH) {

	def NTPDATE = 'sudo -u root -i service ntpd stop; sudo -u root -i ntpdate 10.0.158.153; sudo -u root -i service ntpd start'
	def COPY_DATAWIPE_CONF = 'sudo -u root -i /home/support/copy-datawipe-conf.sh'
	def INTELLEGO_RESTART = 'sudo -u root -i /etc/init.d/intellego restart'
	//def INTELLEGOOAMP_START = 'sudo -u root -i /etc/init.d/intellegooamp start'
	def INTELLEGOOAMP_START = 'sudo -u root -i /opt/intellego/Base/bin/intellegooamp-super start'
	//def CHECKPORTS = 'sudo -u root -i /home/support/checkPorts.sh'
	def CHECKVMC = 'sudo -u root -i /home/support/checkVMC.sh'

    
	    try {
			node(VMC_IP) {
				sh NTPDATE
				sh 'sudo -u root -i /home/support/install.sh -b ' + INTELLEGO_CODE_BRANCH
			}
					
			node(INTELLEGO_IP) {
				sh NTPDATE
				sh 'sudo -u root -i /home/support/install.sh -b ' + INTELLEGO_CODE_BRANCH
				sh COPY_DATAWIPE_CONF
				sh INTELLEGO_RESTART
				sh INTELLEGOOAMP_START
				//sh CHECKPORTS
			}
					
			node(VMC_IP) {
				sh CHECKVMC
			}
		}
		catch(err){
				
			throw err
		}

}

/*

def run_suite(suite, env, ALL_LOGS_DIR) {

	//try { 
		//deleteDir()
		//git url: 'git@bitbucket.org:ss8/intellego-rest-api.git', branch: RESTAPI_BRANCH
		sh ("./gradlew -Dreporting=${REPORTING} -DbuildLogUrl=BUILD_URL/console -DpipelineName=Intellego-CI-Coded-Pipeline -Dsuite=resources/suites/${suite}.xml -Denv=${env} run > ${ALL_LOGS_DIR}/${suite}.log 2>&1", returnStdout:true)

		
			; \
		    cp build/reports/tests/emailable-report.html  build/reports/tests/${suite}.html ; \
			cp build/reports/tests/${suite}.html ${ALL_LOGS_DIR}", returnStdout:true)
			
		
		
	
        //sh 'zip -j ' + TMPDIR + '/level1.zip build/reports/tests/level1.html'
        sh 'echo "<br>" >> ' + TMPDIR + '/Summary.html' 
        sh 'echo "--------------" >> ' + TMPDIR + '/Summary.html' 
        sh 'echo "<br>" >> ' + TMPDIR + '/Summary.html' 
        sh 'echo ' + suite + ' >> ' + TMPDIR + '/Summary.html' 
        sh 'echo "<br>" >> ' + TMPDIR + '/Summary.html' 
        sh 'echo "--------------" >> ' + TMPDIR + '/Summary.html' 
        sh 'echo "<br>" >> ' + TMPDIR + '/Summary.html' 
        sh 'grep failed build/reports/tests/testng-results.xml | head -1 | sed -e "s/<//" -e "s/>//" -e "s/testng-results //" >> ' + TMPDIR + '/Summary.html'
        sh ('grep -wq \"SUCCESSFUL\" ' + suite + '.log ; echo $?')
    } // try block closing
    catch(err) {
		currentBuild.result = 'SUCCESS' // If not set to SUCCESS, the pipeline interprets to an entire failed build
        //TESTS_FAILED++  // increment the count of tests failed
        echo "False. Successful not found.  need to copy"
        sh 'cp ' + suite + '.log build/reports/tests/' + suite +'.html ' + TMPDIR
    } // catch block closing

	
} // end of def run_suite        

*/



