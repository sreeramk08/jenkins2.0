#!groovy

def update_rhel7_repo() {
	echo "Running a repo sync with Redhat 7"
	ws('/home/iso-build-user/scripts'){
		// Try to register with redhat first
		try { 
			sh 'sudo  ./subscribe.sh'
		}
		catch(err){ // If registration failed, unregister and re-register
			sh 'sudo  ./unregister.sh'
			sh 'sudo  ./subscribe.sh'
		}
		// Try to sync
		try { // sync repo
			sh 'sudo ./repo_sync.sh'
		}
		catch (err) {
			currentBuildResult = 'FAILURE'
			sh 'sudo  ./unregister.sh' // unregister before exiting
			sh 'sudo ./unregister.sh'
			emailext body: 'Reposync for RHEL-7 failed ', attachLog: true, subject: 'Reposync for RHEL-7 failed', to: MAILING_LIST
			throw err
		}
		// if we got here the repo sync was successful
		sh 'sudo ./rhel7_repofix.sh' //  Fix issues in repo
		// Unsubscribe once done
		sh 'sudo ./unregister.sh'
	}
}

def update_rhel6_repo() {
	echo "Running a repo sync with Redhat 6"
    try { // Try to register with redhat first
        sh 'sudo  /home/victorhugo/repos/subscribe.sh'
    }
    catch(err){ // If registration failed, unregister and re-register
		sh 'sudo  /home/victorhugo/repos/unregister.sh'
        sh 'sudo  /home/victorhugo/repos/subscribe.sh'
    }
    // Try to sync
    try { 
		sh 'sudo  /home/victorhugo/repos/repoSync-NG.sh'
    }
    catch (err) {
		currentBuildResult = 'FAILURE'
        sh 'sudo /home/victorhugo/repos/unregister.sh' // unregister before exiting
        emailext body: 'Reposync for RHEL-6 failed ', attachLog: true, subject: 'Reposync for RHEL-6 failed', to: MAILING_LIST
		throw err
    }
    // if we got here the repo sync was successful
    sh 'sudo  /home/victorhugo/repos/clean.sh' // Cleanup
    sh 'sudo /home/victorhugo/repos/repoFix.sh' //  Fix issues in repo
    // Unsubscribe once done
    sh 'sudo  /home/victorhugo/repos/unregister.sh'
}

stage ('rpm sync') {

    node {
        
            echo "Running rpm sync..."
            
            if (PLATFORM == 'RHEL-6' ) {
				if (RPM_SYNC == 'true' ) {
					node ('rhel6-iso-build-machine') {
						update_rhel6_repo()
					} // end of node
				}
            } // end of rhel6 block
            
            if ( PLATFORM == 'RHEL-7' ) {
				if ( RPM_SYNC == 'true' ) {
					node ('rhel7-repo') {
						update_rhel7_repo()
					}
				}
				node ('rhel7-iso'){
					ws('/home/iso-build-user/ISO_BUILD'){
						git url: 'ssh://git@10.0.135.6/platform.git', branch: 'master'
					}
					
					def DIR = '/home/iso-build-user/ISO_BUILD/platform-isos/' + PLATFORM + '/' + PROJECT

					ws("${DIR}") {
						try {
							if ( PROJECT == 'sensor' ){
								// Only for sensor the ask is to append a rhel7 next to the version
								sh(script: "sudo gmake nuke; sudo gmake iso RELEASE=${VERSION}")
							}
							else {
								sh(script: "sudo gmake nuke; sudo gmake iso VERSION=${VERSION}")
							}
						}
						catch(err){
							emailext body: 'ISO Creation unsuccessful!', attachLog: true, subject: 'ISO Creation unSuccessfull', to: MAILING_LIST
							currentBuild.result = 'FAILURE'
						}
					} // ws block
				}
            } // end of rhel7 block
   
    } // end of node
} // end of rpm sync stage



// Email success
node {
	  emailext body: 'ISO Creation successful!', attachLog: true, subject: 'ISO Creation Successfull', to: MAILING_LIST
} 


