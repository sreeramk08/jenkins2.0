/*
 This pipeline code will build a Intellego binary, install on servers and run tests
*/

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
  
  // If prebuilt-binary is present, don't build from scratch
  if ( PREBUILT_BINARY_PATH ) {
    echo "Don't rebuild!!"
    def DIR = PREBUILT_BINARY_PATH

    node ('10.0.158.153') {
      
      ws("${DIR}"){
        archive '*.bin'
      }
      
    } //node
  } //if block
  else {
    echo "Building from scratch..."
    node ('10.0.158.153') {
      ws('/home/support/intellego') {
        echo "Checking out code..."
        git url: 'ssh://git@10.0.135.6/intellego.git', branch: INTELLEGO_CODE_BRANCH
      }

    // Cleanup old binary
    sh 'sudo rm -rf /home/support/bin/REL_6.5/root/*'

    // Generate name for the new BINARY based on timestamp
    BINARY = INTELLEGO_VERSION + '.' + INTELLEGO_CODE_BRANCH + '.' + env.BUILD_TIMESTAMP

    ws('/home/support/intellego/build_tool') {
      sh 'sudo ./build-intellego.sh ' + BINARY
    }

    def DIR = '/home/support/bin/REL_' + INTELLEGO_VERSION + '/root/' + BINARY
      ws("${DIR}") {
        archive '*.bin'
      }
    }// node
  } //else

stage 'Copy Binary to Nodes'

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
     //end of node
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
  'Node 152': {
    node('10.0.158.152') {
      unarchive mapping: ['*.bin' : '.']
      sh COPY_BINARY
    } //end of node
  }, // end of 152
  'Node 161': {
    node('10.0.158.161') {
      unarchive mapping: ['*.bin' : '.']
      sh COPY_BINARY
    } //end of node
  } // end of 161


stage 'Install and Test'
  
  def NTPDATE = 'sudo -u root -i service ntpd stop; sudo -u root -i ntpdate 10.0.158.153; sudo -u root -i service ntpd start'
  def COPY_DATAWIPE_CONF = 'sudo -u root -i /home/support/copy-datawipe-conf.sh'
  def INTELLEGOOAMP_START = 'sudo -u root -i /etc/init.d/intellego restart; sudo -u root -i /etc/init.d/intellegooamp start'
  def CHECKPORTS = 'sudo -u root -i /home/support/checkPorts.sh'   
  def CHECKVMC = 'sudo -u root -i /home/support/checkVMC.sh'
  def TMPDIR = '/tmp/rest-api-logs-' + INTELLEGO_CODE_BRANCH

  //Create a temp directory for REST API results
  node('master') {
    sh 'mkdir -p  ' + TMPDIR
  }
 
  parallel '134-148': {

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
  
    //REST API Tests
    node('master') {
      deleteDir()
      // Checkout the rest-api code
      git url: 'git@bitbucket.org:ss8/intellego-rest-api.git', branch: RESTAPI_BRANCH

      try {
        sh './gradlew -Dreporting=' + REPORTING + ' -DbuildLogUrl=' + env.BUILD_URL + '/console -DpipelineName=' + pipelineName + ' -Dsuite=resources/suites/v2_level1_tests.xml -Denv=resources/config/qa-at-158-148.yaml run > level1.log 2>&1'
      }
      catch(err) {
        currentBuild.result = 'SUCCESS'
        sh 'cp build/reports/tests/emailable-report.html   build/reports/tests/level1.html'
        sh 'zip -j ' + TMPDIR + '/level1.zip build/reports/tests/level1.html'
        sh 'cp level1.log build/reports/tests/level1.html ' + TMPDIR
      }

      try {
       sh './gradlew -Dreporting=' + REPORTING + ' -DbuildLogUrl=' + env.BUILD_URL + '/console -DpipelineName=' + pipelineName + ' -Dsuite=resources/suites/v2_level2_tests.xml -Denv=resources/config/qa-at-158-148.yaml run > level2.log 2>&1'
      }
      catch(err){
        currentBuild.result = 'SUCCESS'
        sh 'cp build/reports/tests/emailable-report.html   build/reports/tests/level2.html'
        sh 'zip -j ' + TMPDIR + '/level2.zip build/reports/tests/level2.html'
        sh 'cp level2.log build/reports/tests/level2.html ' + TMPDIR
      }

      try {
        sh './gradlew -Dreporting=' + REPORTING + ' -DbuildLogUrl=' + env.BUILD_URL + '/console -DpipelineName=' + pipelineName + ' -Dsuite=resources/suites/v2_kddi_tests.xml -Denv=resources/config/qa-at-158-148.yaml run > kddi.log 2>&1'
      }
      catch(err){
        currentBuild.result = 'SUCCESS'
        sh 'cp build/reports/tests/emailable-report.html   build/reports/tests/kddi.html'
        sh 'zip -j ' + TMPDIR + '/kddi.zip build/reports/tests/kddi.html'
        sh 'cp kddi.log build/reports/tests/kddi.html ' + TMPDIR
      }

      //archive 'build/reports/tests/kddi.html, build/reports/tests/level1.html, build/reports/tests/level2.html'
    } //node master

  }, //end of 134-148

  '151-152': {
    // Install Intellego DPE on Node 152'

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

    //REST API Tests'

    node('master') {
      
      deleteDir()
      git url: 'git@bitbucket.org:ss8/intellego-rest-api.git', branch: RESTAPI_BRANCH

      try {
        sh './gradlew -Dreporting=' + REPORTING + ' -DbuildLogUrl=' + env.BUILD_URL + '/console -DpipelineName=' + pipelineName + ' -Dsuite=resources/suites/v2_level3_tests.xml -Denv=resources/config/qa-at-158-151.yaml run > level3.log 2>&1'
      }
      catch(err) {
        currentBuild.result = 'SUCCESS'
        sh 'cp build/reports/tests/emailable-report.html   build/reports/tests/level3.html'
        sh 'zip -j ' + TMPDIR + '/level3.zip build/reports/tests/level3.html'
        sh 'cp level3.log build/reports/tests/level3.html ' + TMPDIR
      }

      try {
        sh './gradlew -Dreporting=' + REPORTING + ' -DbuildLogUrl=' + env.BUILD_URL + '/console -DpipelineName=' + pipelineName + ' -Dsuite=resources/suites/v2_all_tests.xml -Denv=resources/config/qa-at-158-151.yaml run > all-tests.log 2>&1'
      }
      catch(err){
        currentBuild.result = 'SUCCESS'
        sh 'cp build/reports/tests/emailable-report.html   build/reports/tests/all-tests.html'
        sh 'zip -j ' + TMPDIR + '/all-tests.zip build/reports/tests/all-tests.html'
        sh 'cp all-tests.log build/reports/tests/all-tests.html ' + TMPDIR
      }

      //archive 'build/reports/tests/level3.html, build/reports/tests/all-tests.html'

    }//node
  }, //151-152

  '131-132': {
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
    //REST API Reporting + DataWipe + IO Workflow'
    node('master') {
      deleteDir()
      git url: 'git@bitbucket.org:ss8/intellego-rest-api.git', branch: RESTAPI_BRANCH
       
      try {
        sh './gradlew -Dreporting=' + REPORTING + ' -DbuildLogUrl=' + env.BUILD_URL + '/console -DpipelineName=' + pipelineName + ' -Dsuite=resources/suites/v2_reporting_tests.xml -Denv=resources/config/qa-at-158-131.yaml run > reporting.log 2>&1'
      }
      catch(err) {
        currentBuild.result = 'SUCCESS'
        sh 'cp build/reports/tests/emailable-report.html   build/reports/tests/reporting.html'
        sh 'zip -j ' + TMPDIR + '/reporting.zip build/reports/tests/reporting.html'
        sh 'cp reporting.log build/reports/tests/reporting.html ' + TMPDIR
      }

      try {
        sh './gradlew -Dreporting=' + REPORTING + ' -DbuildLogUrl=' + env.BUILD_URL + '/console -DpipelineName=' + pipelineName + ' -Dsuite=resources/suites/v2_datawipe_tests.xml -Denv=resources/config/qa-at-158-131.yaml run > datawipe.log 2>&1'
      }
      catch(err) {
        currentBuild.result = 'SUCCESS'
        sh 'cp build/reports/tests/emailable-report.html   build/reports/tests/datawipe.html'
        sh 'zip -j ' + TMPDIR + '/datawipe.zip build/reports/tests/datawipe.html'
        sh 'cp datawipe.log build/reports/tests/datawipe.html ' + TMPDIR
      }

      try {
        sh './gradlew -Dreporting=' + REPORTING + ' -DbuildLogUrl=' + env.BUILD_URL + '/console -DpipelineName=' + pipelineName + ' -Dsuite=resources/suites/v2_io_tests.xml -Denv=resources/config/qa-at-158-131.yaml run > io-tests.log 2>&1'
      }
      catch(err) {
        currentBuild.result = 'SUCCESS'
        sh 'cp build/reports/tests/emailable-report.html   build/reports/tests/io-tests.html'
        sh 'zip -j ' + TMPDIR + '/io-tests.zip build/reports/tests/io-tests.html'
        sh 'cp io-tests.log build/reports/tests/io-tests.html ' + TMPDIR
      }
      //archive 'build/reports/tests/reporting.html, build/reports/tests/datawipe.html, build/reports/tests/io-tests.html'
    }//node
  }, // 131-132

  '147-161': {
  
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

    //REST API v2 regression and Telephony'
    node('master') {
      deleteDir()
      git url: 'git@bitbucket.org:ss8/intellego-rest-api.git', branch: RESTAPI_BRANCH
    
    try {
      sh './gradlew -Dreporting=' + REPORTING + ' -DbuildLogUrl=' + env.BUILD_URL + '/console -DpipelineName=' + pipelineName + ' -Dsuite=resources/suites/v2_regression_tests.xml -Denv=resources/config/qa-at-158-161.yaml run > regression.log 2>&1'
    }
    catch(err) {
      currentBuild.result = 'SUCCESS'
      sh 'cp build/reports/tests/emailable-report.html   build/reports/tests/regression.html'
        sh 'zip -j ' + TMPDIR + '/regression.zip build/reports/tests/regression.html'
        sh 'cp regression.log build/reports/tests/regression.html ' + TMPDIR
    }

    try {
      sh './gradlew -Dreporting=' + REPORTING + ' -DbuildLogUrl=' + env.BUILD_URL + '/console -DpipelineName=' + pipelineName + ' -Dsuite=resources/suites/v2_telephony_tests.xml -Denv=resources/config/qa-at-158-161.yaml run > telephony.log 2>&1'
    }
    catch(err) {
      currentBuild.result = 'SUCCESS'
      sh 'cp build/reports/tests/emailable-report.html   build/reports/tests/telephony.html'
      sh 'zip -j ' + TMPDIR + '/telephony.zip build/reports/tests/telephony.html'
      sh 'cp telephony.log build/reports/tests/telephony.html ' + TMPDIR
    }
   
    //archive 'build/reports/tests/regression.html, build/reports/tests/telephony.html'
    
    archive 'regression.zip, telephony.zip'

    } //node
  } // 147-161
} // timestamps


  // Email after job completes 
  node ('master') {
       ws ("${TMPDIR}") {
          emailext attachmentsPattern: '*.log, *.html', body: '', subject: 'Job is Complete!', to: MAILING_LIST 
       }
  }
