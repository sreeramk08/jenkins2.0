/*
 This pipeline code will build a Intellego binary, install on servers and run tests
*/

def TESTS_FAILED = '0'
def TMPDIR = '/tmp/rest-api-logs-' + INTELLEGO_CODE_BRANCH

  //Create a temp directory for REST API results
  node('jenkins-slave-1') {
    sh 'rm -rf ' + TMPDIR
    sh 'mkdir -p ' + TMPDIR
  }


def run_suite(suite, env) {

  def TMPDIR = '/tmp/rest-api-logs-' + INTELLEGO_CODE_BRANCH

  try { 
    sh './gradlew -Dreporting=' + REPORTING + ' -DbuildLogUrl=BUILD_URL/console -DpipelineName=Intellego-CI-Coded-Pipeline -Dsuite=resources/suites/' + suite + '.xml -Denv=resources/config/' + env + ' run | tee ' + suite + '.log 2>&1'
    sh 'cp build/reports/tests/emailable-report.html   build/reports/tests/' + suite+ '.html'
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


timestamps {

stage 'Build Intellego binary'

  /*
  // "Send an email"
  node ('master') {
    mail (to: MAILING_LIST,
    subject: "Job ${env.JOB_NAME} is running",
    body: 'Parameters - Code: ' + INTELLEGO_CODE_BRANCH + ' RESTAPI: ' + RESTAPI_BRANCH );
  }
  */

  if ( ONLY_RUN_TESTS == 'false' ) {

    // If prebuilt-binary is present, don't build from scratch
    if ( PREBUILT_BINARY_PATH ) {

      echo " ******* Skipping binary building steps ********"
      def DIR = PREBUILT_BINARY_PATH
      node ('intellego-build-machine') {
        ws("${DIR}"){
          archive '*.bin'
        }
      } 
    } //if block
    else {

      echo " ******** Building Intellego Binary *********"
      node ('intellego-build-machine') {
     
        try {
          ws('/home/support/intellego') {
            echo "Checking out code..."
            git url: 'ssh://git@10.0.135.6/intellego.git', branch: INTELLEGO_CODE_BRANCH
          }

          // Cleanup old binary
          sh 'sudo rm -rf /home/support/bin/REL_' + INTELLEGO_VERSION + '/root/*'

          // Generate name for the new BINARY based on timestamp
          BINARY = INTELLEGO_VERSION + '.' + INTELLEGO_CODE_BRANCH + '.' + env.BUILD_TIMESTAMP

          ws('/home/support/intellego/build_tool') {
            sh 'sudo ./build-intellego.sh ' + BINARY
          }

          def DIR = '/home/support/bin/REL_' + INTELLEGO_VERSION + '/root/' + BINARY
            ws("${DIR}") {
            archive '*.bin'
          }
        } // try block

        catch(err){
          // Email in case the build failed
          emailext body: 'BUILD_URL = ' + env.BUILD_URL + '/consoleFull', subject: 'Nightly coded pipeline build has failed! ', to: MAILING_LIST
        } 
      }// node
    } // end of else block
  } // end of ONLY_RUN_TESTS 

stage 'Copy Binary to Nodes'
  
  if ( ONLY_RUN_TESTS == 'false' ) {

    def COPY_BINARY='sudo rm -f /SS8/SS8_Intellego.bin; sudo mv *.bin /SS8/SS8_Intellego.bin; sudo chmod 775 /SS8/SS8_Intellego.bin'
  
    parallel 'Node 131': {
      node('10.0.158.131') {
        unarchive mapping: ['*.bin' : '.']
        sh COPY_BINARY
      } //end of node
    }, // end of 131
    'Node 132': {
      node('10.0.158.132') {
        unarchive mapping: ['*.bin' : '.']
        sh COPY_BINARY
      } //end of node
     }, // end of 132
    'Node 134': {
      node('10.0.158.134') {
        unarchive mapping: ['*.bin' : '.']
        sh COPY_BINARY
      } //end of node
    }, // end of 134
    'Node 147': {
      node('10.0.158.147') {
        unarchive mapping: ['*.bin' : '.']
        sh COPY_BINARY
      } //end of node
    }, // end of 147
    'Node 148': {
      node('10.0.158.148') {
        unarchive mapping: ['*.bin' : '.']
        sh COPY_BINARY
      } //end of node
    }, // end of 148
    'Node 151': {
      node('10.0.158.151') {
        unarchive mapping: ['*.bin' : '.']
        sh COPY_BINARY
      } //end of node
    }, // end of 151
    'Node 161': {
      node('10.0.158.161') {
        unarchive mapping: ['*.bin' : '.']
        sh COPY_BINARY
      } //end of node
    }, // end of 161
    'Node 152': {
      node('10.0.158.152') {
        unarchive mapping: ['*.bin' : '.']
        sh COPY_BINARY
      } //end of node
    } // end of 152
  } // end of ONLY_RUN_TESTS block

stage 'Install and Test'
  
  def NTPDATE = 'sudo -u root -i service ntpd stop; sudo -u root -i ntpdate 10.0.158.153; sudo -u root -i service ntpd start'
  def COPY_DATAWIPE_CONF = 'sudo -u root -i /home/support/copy-datawipe-conf.sh'
  def INTELLEGOOAMP_START = 'sudo -u root -i /etc/init.d/intellego restart; sudo -u root -i /etc/init.d/intellegooamp start'
  def CHECKPORTS = 'sudo -u root -i /home/support/checkPorts.sh'
  def CHECKVMC = 'sudo -u root -i /home/support/checkVMC.sh'
    
  parallel '134-148': {

    if ( ONLY_RUN_TESTS == 'false' ) {
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
    } // end of if ONLY_RUN_TESTS
    
    //REST API Tests
    node('jenkins-slave-1') {
      deleteDir()
      // Checkout the rest-api code
      git url: 'git@bitbucket.org:ss8/intellego-rest-api.git', branch: RESTAPI_BRANCH
      
      // Capture all details in Summary.html before running all tests
      sh 'echo "<h3>Nightly build pipeline has completed!</h3>" > ' + TMPDIR + '/Summary.html'
      sh 'echo "<b>Build Details:</b>" >> ' + TMPDIR + '/Summary.html'
      sh 'echo "<p>Console Output: <a href= "' + env.BUILD_URL + '"consoleFull>"' + env.BUILD_URL + '"consoleFull</a>"' + ' >> ' + TMPDIR + '/Summary.html' 
      //sh 'echo INTELLEGO_BINARY: ' + BINARY + ' >> ' + TMPDIR  + '/Summary.html'
      sh 'echo "<p>" >> ' + TMPDIR + '/Summary.html'
      sh 'echo "<b>Rest API Test Results:</b>" >> ' + TMPDIR + '/Summary.html' 

      
      if ( run_level1 == 'true' ) {
        run_suite ( 'level1_tests', 'qa-at-158-148.yaml' )
      } 
      
      if ( run_level2== 'true' ) {
        run_suite ( 'level2_tests', 'qa-at-158-148.yaml' )
      } 

      if ( run_kddi== 'true' ) {
        run_suite ( 'kddi_tests', 'qa-at-158-148.yaml' )
      } 

    } // End of node jenkins-slave-1 block

  }, // end of 134-148 block

  '151-152': {

    // Install Intellego DPE on Node 152'
    if ( ONLY_RUN_TESTS == 'false' ) {
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
   } // end of ONLY_RUN_TESTS block

    //REST API Tests'
    node('jenkins-slave-1') {
      deleteDir()
      git url: 'git@bitbucket.org:ss8/intellego-rest-api.git', branch: RESTAPI_BRANCH

      if ( run_level3 == 'true' ) {
        run_suite ( 'level3_tests', 'qa-at-158-151.yaml' )
      } 
     
      if ( run_alltests== 'true' ) {
        run_suite ( 'all_tests', 'qa-at-158-151.yaml' )
      } 

    }//node


  }, //151-152

  '131-132': {

    if ( ONLY_RUN_TESTS == 'false' ) {
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
    } // end of ONLY_RUN_TESTS block
  

    //REST API Reporting + DataWipe + IO Workflow'
    node('jenkins-slave-1') {
    //node('master') {
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
  
    }//node

  }, // 131-132

  '147-161': {
      
    if ( ONLY_RUN_TESTS == 'false' ) {
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
    } // End of RUN_ONLY_TESTS block
 
    //REST API v2 regression and Telephony'
    node('jenkins-slave-1') {
      deleteDir()
      git url: 'git@bitbucket.org:ss8/intellego-rest-api.git', branch: RESTAPI_BRANCH

    if ( run_regression == 'true' ) {
        run_suite ( 'regression_tests', 'qa-at-158-161.yaml' )
      } 
      
      if ( run_telephony== 'true' ) {
        run_suite ( 'telephony_tests', 'qa-at-158-161.yaml' )
      } 

    } //node
      
  } // End of 147-161 and parallel block

  node('jenkins-slave-1'){
     ws ("${TMPDIR}") {

        echo "**************** Sending logs from here ********************"
        //sh 'echo "\nNo. of TEST SUITES FAILED: " ' + TESTS_FAILED + ' >> ' + TMPDIR + '/Summary.html' 
        try{
          emailext attachmentsPattern: '*.log, *.html', body: 'BUILD_URL = ' + env.BUILD_URL, subject: 'Nightly coded pipeline build has completed! ', to: MAILING_LIST
          //sh 'rm -rf ' + TMPDIR
          sh 'zip -j Failed_Test_Results.zip ' + TMPDIR + '/*'
          archive 'Failed_Test_Results.zip, Summary.html'
        }
        catch(err){
          currentBuild.result = 'SUCCESS'    
        }
     } //end of ws block
  } //end of node block
} // End of timestamp block  

  




