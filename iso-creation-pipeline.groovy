#!groovy

currentBuild.displayName = PROJECT + '-' + VERSION

if (RPM_SYNC == 'true' ) {

	stage ('Sync RPMs') {
	
		if (PLATFORM == 'RHEL-6' ) {
			node ('rhel6-iso-build-machine') {
				update_rhel6_repo()
			}
		}
		else if ( PLATFORM == 'RHEL-7' ) {
			node ('rhel7-repo') {
				update_rhel7_repo()
			}
		}
	}
}

stage ('Build ISO') {

	if (PLATFORM == 'RHEL-6' ) {
	
		node ('rhel6-iso-build-machine') {		
			ws('/home/iso-build-user/ISO_BUILD'){
				git url: 'ssh://git@10.0.135.6/platform.git', branch: 'master'
			}
					
			def DIR = '/home/iso-build-user/ISO_BUILD/platform-isos/' + PLATFORM + '/' + PROJECT

			ws("${DIR}") {
				
					sh(script: "sudo gmake nuke; sudo gmake iso VERSION=${VERSION}")
					def exists = sh(script: "ls dist/*.iso ", returnStdout:true)
					echo "Got exists as: " + exists
								
					// Iso creation was successful
					if (exists) {
					    echo "ISO's are available! Renaming them..."
						def ISO = sh(script:"cd dist; ls *.iso | grep -v upgrade", returnStdout: true).trim()
						sh 'sudo mv dist/' + ISO + ' dist/intellego-spl-new-' + VERSION + '.iso'
						def UP_ISO = sh(script:"cd dist; ls *.iso | grep upgrade", returnStdout: true).trim()
						sh 'sudo mv dist/' + UP_ISO + ' dist/intellego-spl-new-' + VERSION + '-upgrade.iso'
						echo "Copying to ISO Datastore"
						sh 'scp dist/*.iso isoadmin@10.0.155.54:/ISOFolder/SPL'
						emailext body: 'ISO Creation successful!', attachLog: true, subject: 'ISO Creation Completed', to: MAILING_LIST
					} 
					else {
						
						emailext body: 'ISO Creation unsuccessful!', attachLog: true, subject: 'ISO Creation unSuccessfull', to: MAILING_LIST
						currentBuild.result = 'FAILURE'
						throw err
					}
			} // ws block
		} // end of node
	
	}
	else (PLATFORM == 'RHEL-7' ) {
	
		node ('rhel7-iso'){
			ws('/home/iso-build-user/ISO_BUILD'){
				git url: 'ssh://git@10.0.135.6/platform.git', branch: 'master'
			}
					
			def DIR = '/home/iso-build-user/ISO_BUILD/platform-isos/' + PLATFORM + '/' + PROJECT

			ws("${DIR}") {
				if ( PROJECT == 'sensor' ){
					// Only for sensor the ask is to append a rhel7 next to the version
					sh(script: "sudo gmake nuke; sudo gmake iso RELEASE=${VERSION}")
				}
				else {
					sh(script: "sudo gmake nuke; sudo gmake iso VERSION=${VERSION}")
				}
				def exists = sh(script: "ls dist/*.iso ", returnStdout:true)
				echo "Got exists as: " + exists
								
				// Iso creation was successful
				if (exists) {
				    echo "ISO's are available! Renaming them..."
					def ISO = sh(script:"cd dist; ls *.iso | grep -v upgrade", returnStdout: true).trim()
					sh 'sudo mv dist/' + ISO + ' dist/intellego-spl-new-' + VERSION + '.iso'
					def UP_ISO = sh(script:"cd dist; ls *.iso | grep upgrade", returnStdout: true).trim()
					sh 'sudo mv dist/' + UP_ISO + ' dist/intellego-spl-new-' + VERSION + '-upgrade.iso'
					echo "Copying to ISO Datastore"
					sh 'scp dist/*.iso isoadmin@10.0.155.54:/ISOFolder/SPL'
					emailext body: 'ISO Creation successful!', attachLog: true, subject: 'ISO Creation Completed', to: MAILING_LIST
				} 
				else {
						
					emailext body: 'ISO Creation unsuccessful!', attachLog: true, subject: 'ISO Creation unSuccessfull', to: MAILING_LIST
					currentBuild.result = 'FAILURE'
					throw err
				}
			}
		}

	}


}



//Methods

def update_rhel7_repo() {
	echo "Running a repo sync with Redhat 7"
	ws('/home/iso-build-user/scripts'){
		try { 
			sh 'sudo  ./unregister.sh; ' //force unregister first
			sh 'sudo  ./subscribe.sh | tee /tmp/aa '  // then try to subscribe
			//ERROR = sh(script: "grep 'No subscriptions are available' /tmp/aa ; echo \$?", returnStdout: true).trim()
			//if ( ERROR == '0' ) {
			//	currentBuild.result = 'FAILURE'
			//	emailext body: 'Reposync for RHEL-7 failed', attachLog: true, subject: 'No Subscritptions are available', to: MAILING_LIST
			//	throw err
			//}
		}
		catch(err){ // If registration failed, unregister and re-register
			emailext body: 'Subscritption for RHEL-7 failed ', attachLog: true, subject: 'Reposync for RHEL-7 failed during subscription', to: MAILING_LIST
		}
		// Try to sync
		try { // sync repo
			sh 'sudo ./repo_sync.sh'
		}
		catch (err) {
			currentBuild.result = 'FAILURE'
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
	ws('/home/iso-build-user/scripts'){
		try { 
			sh 'sudo  ./unregister.sh ; sudo ./subscribe.sh ' 
		}
		catch(err){ 
			emailext body: 'Subscritption for RHEL-6 failed ', attachLog: true, subject: 'Subscritption for RHEL-6 failed', to: MAILING_LIST
		}
		// Try to sync
		
		sh 'sudo  ./repoSync-NG.sh'
		
		// if we got here the repo sync was successful
		sh 'sudo  ./clean.sh ; sudo ./repoFix.sh ; sudo  ./unregister.sh' // Cleanup
	}
}
