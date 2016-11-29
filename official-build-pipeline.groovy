/*
 This pipeline code will build a Intellego Official binary, install on pre determined servers and run tests
*/

def TESTS_FAILED = '0'
def TMPDIR = '/tmp/rest-api-official-logs-' + RESTAPI_BRANCH

	//Create a temp directory for REST API results
	node('jenkins-slave') {
		sh 'rm -rf ' + TMPDIR
		sh 'mkdir -p ' + TMPDIR
	}

def first_build_changes(INTELLEGO_VERSION, VERSION_TO_BUILD){
		/*
			A First build can be one that is first in the range. Ex: 6.6.2.3.<1> or a new intellego version. Ex: 6.6 -> 6.7
			In both cases, we need to get the last tag applied in 6.6. How we determine the 6.6 is different. 
		*/
		
	    def TMPDIR = '/tmp/rest-api-official-logs-' + RESTAPI_BRANCH
        echo "Determining changes for a first build:" + VERSION_TO_BUILD
        
	    //First see if there is already a last build in the intellego version range
		try {
		    LAST_TAG = sh(script: "git describe --tags --abbrev=0 --match ${INTELLEGO_VERSION}*", returnStdout: true).trim()
		}
		catch(err) { 
		    /*
				If its a new intellego version range, the above will fail
				For the very first build in the range, we need to go back in the intellego version
			*/
			PREV = sh(script: "python -c \"print(${INTELLEGO_VERSION} - 0.1)\" ", returnStdout: true).trim()
			LAST_TAG = sh(script: "git describe --tags --abbrev=0 --match ${PREV}*", returnStdout: true ).trim()
        }
		
		echo "The last tag obtained was: " + LAST_TAG
        
		// Now to get the changes into a text file
        sh 'echo "Changes between: ' + LAST_TAG + ' to ' + VERSION_TO_BUILD + ' " > Changes.log '
        sh 'echo "-----------------------------------------\n" >> Changes.log' 
        sh 'git log --pretty=format:"%s    %an" ' + LAST_TAG + '...' + VERSION_TO_BUILD + ' >> Changes.log'
        archive 'Changes.log'
}

def build_changes(PREV_BUILD_NUMBER, VERSION_TO_BUILD) {
    
	    def TMPDIR = '/tmp/rest-api-official-logs-' + RESTAPI_BRANCH
        echo "Determining changes between: " + PREV_BUILD_NUMBER + ' to ' + VERSION_TO_BUILD
	    sh 'echo "Changes between: ' + PREV_BUILD_NUMBER + ' to ' + VERSION_TO_BUILD + ' " > Changes.log '
        sh 'echo "-----------------------------------------\n" >> Changes.log' 
        sh 'git log --pretty=format:"%s    %an" ' + PREV_BUILD_NUMBER + '...' + VERSION_TO_BUILD + ' >> Changes.log'
        archive 'Changes.log'
}

def run_suite(suite, env) {

	def TMPDIR = '/tmp/rest-api-official-logs-' + RESTAPI_BRANCH

	try { 
		sh './gradlew -Dreporting=' + REPORTING + ' -DbuildLogUrl=BUILD_URL/console -DpipelineName=Intellego-CI-Coded-Pipeline -Dsuite=resources/suites/' + suite + '.xml -Denv=resources/config/' + env + ' run | tee ' + suite + '.log 2>&1'
		sh 'cp build/reports/tests/emailable-report.html   build/reports/tests/' + suite+ '.html'
        sh 'echo "<br>" >> ' + TMPDIR + '/Summary.HTML' 
        sh 'echo "--------------" >> ' + TMPDIR + '/Summary.HTML' 
        sh 'echo "<br>" >> ' + TMPDIR + '/Summary.HTML' 
        sh 'echo ' + suite + ' >> ' + TMPDIR + '/Summary.HTML' 
        sh 'echo "<br>" >> ' + TMPDIR + '/Summary.HTML' 
        sh 'echo "--------------" >> ' + TMPDIR + '/Summary.HTML' 
        sh 'echo "<br>" >> ' + TMPDIR + '/Summary.HTML' 
        sh 'grep failed build/reports/tests/testng-results.xml | head -1 | sed -e "s/<//" -e "s/>//" -e "s/testng-results //" >> ' + TMPDIR + '/Summary.HTML'
        sh ('grep -wq \"SUCCESSFUL\" ' + suite + '.log ; echo $?')
    } // try block closing
    catch(err) {
        currentBuild.result = 'SUCCESS' // If not set to SUCCESS, the pipeline interprets to an entire failed build
        //TESTS_FAILED++  // increment the count of tests failed
        echo "False. Successful not found.  need to copy"
        sh 'cp ' + suite + '.log build/reports/tests/' + suite +'.html ' + TMPDIR
    } // catch block closing
} // end of def run_suite        


timestamps {

	stage ('Build Intellego binary'){
	
	node {
		// Handle build naming
		if ( ONLY_RUN_TESTS == 'true' ){
			def DIR = PREBUILT_BINARY_PATH
			currentBuild.displayName = sh(script: "basename ${DIR}", returnStdout: true).trim() + '-TESTS_ONLY'
		}
	}
		
	if ( ONLY_RUN_TESTS == 'false' ) {

		// If prebuilt-binary is present, don't build from scratch
		if ( PREBUILT_BINARY_PATH ) {
			echo " ******* Skipping binary building steps ********"
			def DIR = PREBUILT_BINARY_PATH
			currentBuild.displayName = sh(script: "basename ${DIR}", returnStdout: true).trim() + '-PREBUILT_BINARY'
			node ('intellego-official-build-machine') {
			    
				ws("${DIR}"){
					archive '*.bin'
				}
				
			}
		} 
		
		else {  // No prebuilt binary present.  Need to build a binary
			echo " ******** Building Intellego Binary *********"
			node ('intellego-official-build-machine') {
				// intelbld's home directory
				//ws('/mnt/grandprix/homes/intelbld/git/jenkins/jenkins-official-builds') {
					deleteDir()
					try{
						// Try to checkout the code
						echo "Checking out code..."
						//git url: 'ssh://git@10.0.135.6/intellego.git', branch: INTELLEGO_CODE_BRANCH
						sh 'umask 0022; git clone ssh://git@10.0.135.6/intellego.git . ' 
						//sh 'umask 0022; git pull '
						
					}
					catch(err){
						currentBuild.result = 'FAILURE'
						emailext body: 'Could not check out code for: ' + INTELLEGO_CODE_BRANCH, subject: 'Official Build failed', to: MAILING_LIST
						throw err
					}
					
					
					
					//Create a new folder for newer releases
					try{
						// Look under /intellego/bin for a REL_<Intellego version> directory.  If not exists, create it
						sh 'if [[ ! -e /intellego/bin/REL_' + INTELLEGO_VERSION + ' ]]; then mkdir -p /intellego/bin/REL_' + INTELLEGO_VERSION + '/intelbld ; fi'
					}
					catch(err){
						currentBuild.result = 'FAILURE'
						emailext body: 'Failed to create a new directory REL_' + INTELLEGO_VERSION, subject: 'Official build failed!', to: MAILING_LIST
						throw err
					}
			
					// Generate next build number
					def PrevBuildNumber = " "
					def SEARCH = INTELLEGO_VERSION + '.' + MINOR_VERSION + '.' + PATCH_VERSION  // search for 6.6.1.0 
					try{
						//PrevBuildNumber = sh(script: "git describe --tags --abbrev=0 --match ${SEARCH}* | rev | cut -d. -f1 | rev", returnStdout: true).trim()
					    PrevBuildNumber = sh(script: " git tag | grep ${SEARCH}* | tail -1 | rev | cut -d. -f1 | rev", returnStdout: true).trim()
						//echo "Got the last build number from search as: " + PrevBuildNumber
					}
					catch(err){
						// For the very first build, the search will not give anything.  Move on, don't fail the build.
						currentBuild.result = 'SUCCESS'
					}
					
					echo "Got the search for previous number as: "  + PrevBuildNumber

					def FIRST_BUILD = ""
					
					if ( ! PrevBuildNumber ) { // very first build
						echo "First build of its range"
						NEXT_BUILD_NUMBER = 1
						FIRST_BUILD = 'yes'
					}
					else {
						echo "Not the very first build"
						buildNumber = sh(script: "echo ${PrevBuildNumber} | sed 's/0*//' ", returnStdout: true).trim()
						int BN = buildNumber.toInteger()
					    //echo "The build number is: " + BN
					    NEXT_BUILD_NUMBER = BN + 1
					    //echo "The Next build number is: " + NEXT_BUILD_NUMBER
					}
	
					if ( NEXT_BUILD_NUMBER < 10 ) {
						// Add two leading zeros to the version
						VERSION_TO_BUILD = INTELLEGO_VERSION + '.' + MINOR_VERSION +'.' + PATCH_VERSION + '.00' + NEXT_BUILD_NUMBER
					}
					else {
						// Add only one leading zero
						VERSION_TO_BUILD = INTELLEGO_VERSION + '.' + MINOR_VERSION +'.' + PATCH_VERSION + '.0' + NEXT_BUILD_NUMBER
					}
					
					echo "Current build number is: " + VERSION_TO_BUILD
					
					
					// Set the name of the current build in Jenkins
					currentBuild.displayName = VERSION_TO_BUILD
					
					//Tag Git and Build the binary  
					try {
						sh 'git tag -a ' + VERSION_TO_BUILD + ' -m "Intellego Build No. ' + VERSION_TO_BUILD + '"'
						sh 'git push origin ' + VERSION_TO_BUILD
					}
					catch(err){
						// If tagging failed or if the tag already exists, fail the build
						currentBuild.result = 'FAILURE'
						emailext body: 'A tag already exists! ' +  VERSION_TO_BUILD, subject: 'Official build failed!', to: MAILING_LIST
						throw err
					}
					
					//Determine changes depending on if its a first build or not
					if ( FIRST_BUILD == "yes" ){
					   first_build_changes(INTELLEGO_VERSION, VERSION_TO_BUILD) 
					}
					else {
					   PREV_BUILD_NUMBER = INTELLEGO_VERSION + '.' + MINOR_VERSION +'.' + PATCH_VERSION + '.' + PrevBuildNumber // Ex: 6.6.1.0.009
					   build_changes(PREV_BUILD_NUMBER, VERSION_TO_BUILD)
					}
					
					// After changes are calculated, we can build the binary now!
					
					echo ":::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::"
					echo "::::::::: BUILDING INTELLEGO OFFICIAL BINARY " + VERSION_TO_BUILD  + " ::::::::::::"
					echo ":::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::"
				    
					//sh 'chmod +x 3rd_party/3rdParty_libraries_C/codec/lib/libqmcomfortnois e.so' //forcefully change permissions for this file for now	
					sh 'cd build_tool; ./build-intellego.sh ' + VERSION_TO_BUILD  
		
		
					// "Send an email"
					node ('master') {
						mail (to: MAILING_LIST,
						subject: 'START Official Intellego Build:' + VERSION_TO_BUILD + ' SOURCE: ' + INTELLEGO_CODE_BRANCH + ' RESTAPI:' + RESTAPI_BRANCH,
						//body: 'Parameters - Code: ' + INTELLEGO_CODE_BRANCH + ' RESTAPI: ' + RESTAPI_BRANCH );
						body: 'Console log: ' + env.BUILD_URL)
					}			
					// archive the binary to copy to the other nodes
					def DIR = '/intellego/bin/REL_' + INTELLEGO_VERSION + '/intelbld/' + VERSION_TO_BUILD
					
					ws("${DIR}") {
						archive '*.bin'
					}
					
					
				//} // end of ws block	
			}// node
		} // end of else block
	} // end of ONLY_RUN_TESTS 
	} //stage end
		
	stage ('Copy Binary to Nodes'){
		
		if ( ONLY_RUN_TESTS == 'false' ) {

			def COPY_BINARY='sudo rm -f /SS8/SS8_Intellego.bin; sudo mv *.bin /SS8/SS8_Intellego.bin; sudo chmod 775 /SS8/SS8_Intellego.bin'
  
			parallel 'Node 131': {
				node('10.0.158.131') {
					def exists = fileExists '*.bin'
					if (exists) {
						sh 'sudo rm *.bin'
					}
					unarchive mapping: ['*.bin' : '.']
					sh COPY_BINARY
				} //end of node
			}, // end of 131
			'Node 132': {
				node('10.0.158.132') {
					def exists = fileExists '*.bin'
					if (exists) {
						sh 'sudo rm *.bin'
					}
					unarchive mapping: ['*.bin' : '.']
					sh COPY_BINARY
				} //end of node
			}, // end of 132
			'Node 134': {
				node('10.0.158.134') {
					def exists = fileExists '*.bin'
					if (exists) {
						sh 'sudo rm *.bin'
					}
					unarchive mapping: ['*.bin' : '.']
					sh COPY_BINARY
				} //end of node
			}, // end of 134
			
			//'Node 147': {
			//	node('10.0.158.147') {
			//		def exists = fileExists '*.bin'
			//		if (exists) {
			//			sh 'sudo rm *.bin'
			//		}
			//		unarchive mapping: ['*.bin' : '.']
			//		sh COPY_BINARY
			//	} //end of node
			//}, // end of 147
			
			'Node 148': {
				node('10.0.158.148') {
					def exists = fileExists '*.bin'
					if (exists) {
						sh 'sudo rm *.bin'
					}
					unarchive mapping: ['*.bin' : '.']
					sh COPY_BINARY
				} //end of node
			}, // end of 148
			'Node 151': {
				node('10.0.158.151') {
					def exists = fileExists '*.bin'
					if (exists) {
						sh 'sudo rm *.bin'
					}
					unarchive mapping: ['*.bin' : '.']
					sh COPY_BINARY
				} //end of node
			}, // end of 151
			
			//'Node 161': {
			//	node('10.0.158.161') {
			//		def exists = fileExists '*.bin'
			//		if (exists) {
			//			sh 'sudo rm *.bin'
			//		}
			//		unarchive mapping: ['*.bin' : '.']
            //sh COPY_BINARY
			//	} //end of node
			//}, // end of 161
			
			'Node 152': {
				node('10.0.158.152') {
					def exists = fileExists '*.bin'
					if (exists) {
						sh 'sudo rm *.bin'
					}
					unarchive mapping: ['*.bin' : '.']
					sh COPY_BINARY
				} //end of node
			} // end of 152
		} // end of ONLY_RUN_TESTS block
		
	} // stage end
	
	stage ('Install and Test'){
		
		def NTPDATE = 'sudo -u root -i service ntpd stop; sudo -u root -i ntpdate 10.0.158.153; sudo -u root -i service ntpd start'
		def COPY_DATAWIPE_CONF = 'sudo -u root -i /home/support/copy-datawipe-conf.sh'
		def INTELLEGOOAMP_START = 'sudo -u root -i /etc/init.d/intellego restart; sudo -u root -i /etc/init.d/intellegooamp start'
		def CHECKPORTS = 'sudo -u root -i /home/support/checkPorts.sh'
		def CHECKVMC = 'sudo -u root -i /home/support/checkVMC.sh'
    
	parallel '134-148': {
        
		if ( ONLY_RUN_TESTS == 'false' ) {
			timeout(time:1, unit:'HOURS') {
				try {
					//Install Intellego DPE on Node 134
					node('10.0.158.134') {
						sh NTPDATE
						sh 'sudo -u root -i /home/support/install.sh -b ' + INTELLEGO_CODE_BRANCH
					}
					// Install intellego on 148
					node('10.0.158.148') {
						sh NTPDATE
						sh 'sudo -u root -i /home/support/install.sh -b ' + INTELLEGO_CODE_BRANCH
						sh COPY_DATAWIPE_CONF
						sh INTELLEGOOAMP_START
						sh CHECKPORTS
					}
					//Restart vmc on 134
					node('10.0.158.134') {
						sh CHECKVMC
					}
				}
				catch(err){
					emailext attachLog: 'true', subject: 'Installing Intellego: Official build failed at 134-148 step', to: MAILING_LIST
					throw err
				}
			}
		} // end of if ONLY_RUN_TESTS
        
		//REST API Tests
		node('jenkins-slave') {
			deleteDir()
			// Checkout the rest-api code
			git url: 'git@bitbucket.org:ss8/intellego-rest-api.git', branch: RESTAPI_BRANCH
      
			// Capture all details in Summary.HTML before running all tests
			sh 'echo "<b>Build Details:</b>" > ' + TMPDIR + '/Summary.HTML'
			sh 'echo "<p>Intellego Source Branch - " ' + INTELLEGO_CODE_BRANCH + ' >> ' + TMPDIR + '/Summary.HTML'
			sh 'echo "<p>Rest API Branch - " ' + RESTAPI_BRANCH + ' >> ' + TMPDIR + '/Summary.HTML'
			sh 'echo "<p>Console Output - <a href= "' + env.BUILD_URL + '"consoleFull>"' + env.BUILD_URL + '"consoleFull</a>"' + ' >> ' + TMPDIR + '/Summary.HTML' 
			//sh 'echo INTELLEGO_BINARY: ' + VERSION_TO_BUILD + ' >> ' + TMPDIR  + '/Summary.HTML'
			sh 'echo "<p>" >> ' + TMPDIR + '/Summary.HTML'
			sh 'echo "<b>Rest API Test Results:</b>" >> ' + TMPDIR + '/Summary.HTML' 

      
			if ( run_level1 == 'true' ) {
				run_suite ( 'level1_tests', 'qa-at-158-148.yaml' )
			} 
      
			if ( run_level2== 'true' ) {
				run_suite ( 'level2_tests', 'qa-at-158-148.yaml' )
			} 

			if ( run_kddi== 'true' ) {
				run_suite ( 'kddi_tests', 'qa-at-158-148.yaml' )
			} 
		} // End of node jenkins-slave block

	}, // end of 134-148 block

	'151-152': {
        
		// Install Intellego DPE on Node 152'
		if ( ONLY_RUN_TESTS == 'false' ) {
			timeout(time:1, unit:'HOURS') {
				try {
					node('10.0.158.152') {
						sh NTPDATE
						sh 'sudo -u root -i /home/support/install.sh -b ' + INTELLEGO_CODE_BRANCH
					} //end of node
  
					// Install Intellego on Node 151'
					node('10.0.158.151') {
						sh NTPDATE
						sh 'sudo -u root -i /home/support/install.sh -b ' + INTELLEGO_CODE_BRANCH
						sh COPY_DATAWIPE_CONF
						sh INTELLEGOOAMP_START
						sh CHECKPORTS
					} //end of node
  
					//Restart vmc on 152'
					node('10.0.158.152') {
						sh CHECKVMC
					}
				}
				catch(err){
					emailext attachLog: 'true', subject: 'Installing Intellego: Official build failed at 151-152 step', to: MAILING_LIST
					throw err
				}
			}
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

			if ( run_rtf == 'true' ) {
				run_suite ( 'rtf_all', 'qa-at-158-151.yaml' )
			} 
      
			if ( run_regression == 'true' ) {
				run_suite ( 'regression_tests', 'qa-at-158-151.yaml' )
			} 
      
		}//node


	}, //151-152

	'131-132': {
        
		if ( ONLY_RUN_TESTS == 'false' ) {
			timeout(time:1, unit:'HOURS') {
				try {
					//'Install Intellego DPE on Node 132'
					node('10.0.158.132') {
						sh NTPDATE
						sh 'sudo -u root -i /home/support/install.sh -b ' + INTELLEGO_CODE_BRANCH
					} //end of node
  
					//Install Intellego on Node 131'
					node('10.0.158.131') {
						sh NTPDATE
						sh 'sudo -u root -i /home/support/install.sh -b ' + INTELLEGO_CODE_BRANCH
						sh COPY_DATAWIPE_CONF
						sh INTELLEGOOAMP_START
						sh CHECKPORTS
					} //end of node
  
					//Restart vmc on 132'
					node('10.0.158.132') {
						sh CHECKVMC
					} //node
				}
				catch(err){
					emailext attachLog: 'true', subject: 'Installing Intellego: Official build failed at 131-132 step', to: MAILING_LIST
					throw err
				}
			} // end of timeout block
		} // end of ONLY_RUN_TESTS block
        

		//REST API Reporting + DataWipe + IO Workflow'
		node('jenkins-slave') {
			deleteDir()
			git url: 'git@bitbucket.org:ss8/intellego-rest-api.git', branch: RESTAPI_BRANCH

			if ( run_reporting == 'true' ) {
				run_suite ( 'reporting_tests', 'qa-at-158-131.yaml' )
			} 
      
			if ( run_datawipe== 'true' ) {
				run_suite ( 'datawipe_tests', 'qa-at-158-131.yaml' )
			} 

			if ( run_io== 'true' ) {
				run_suite ( 'io_tests', 'qa-at-158-131.yaml' )
			} 
      
			if ( run_telephony== 'true' ) {
				run_suite ( 'telephony_tests', 'qa-at-158-131.yaml' )
			} 
  
		}//node

	}, // 131-132

	'147-161': {
        /*
		if ( ONLY_RUN_TESTS == 'false' ) {
			timeout(time:1, unit:'HOURS') {
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
					emailext attachLog: 'true', subject: 'Installing Intellego: Official build failed at 147-161 step', to: MAILING_LIST
					throw err
				}
			} //end of timeout block
		} // End of RUN_ONLY_TESTS block
 
		//REST API v2 regression and Telephony'
		node('jenkins-slave') {
			deleteDir()
			git url: 'git@bitbucket.org:ss8/intellego-rest-api.git', branch: RESTAPI_BRANCH

		} //node
        
	    */
	}// End of 147-161 and parallel block
    
	} //end stage
	
	stage ('Reporting') {
	
		node('jenkins-slave'){
			ws ("${TMPDIR}") {

				echo "**************** Sending logs from here ********************"
				//sh 'echo "\nNo. of TEST SUITES FAILED: " ' + TESTS_FAILED + ' >> ' + TMPDIR + '/Summary.HTML' 
				try{
					if ( PREBUILT_BINARY_PATH ) { 
						def DIR = PREBUILT_BINARY_PATH
						VERSION_TO_BUILD = sh(script: "basename ${DIR}", returnStdout: true).trim()
					}
					else {
						unarchive mapping: ['*.log' : '.']
					}
				
					emailext attachmentsPattern: '*.log, *.html', mimeType: 'text/html', body: '${FILE,path="' + TMPDIR + '/Summary.HTML"}', subject: 'END Official Coded Pipeline Build: ' + VERSION_TO_BUILD + ' SOURCE - ' + INTELLEGO_CODE_BRANCH + ' RESTAPI - ' + RESTAPI_BRANCH, to: MAILING_LIST
					sh 'zip -j All_Test_Results.zip ' + TMPDIR + '/*'
					archive 'All_Test_Results.zip, Summary.HTML, Changes.log'
					
				}
				catch(err){
				    echo "***************** Could not send mail! **************"
					currentBuild.result = 'SUCCESS'    
				}
			} //end of ws block
		} //end of node block
	} // end of stage
} // End of timestamp block  

  








