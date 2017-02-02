#!groovy

currentBuild.displayName = PROJECT + '-' + VERSION	

stage('Copy to Releases'){

	
		if (PLATFORM == 'RHEL-6') {
				
			node('rhel6-iso-build-machine'){
				deleteDir()
				git url: 'git@bitbucket.org:ss8/scripts.git', branch: 'master'
				sh 'chmod +x iso/push_to_releases.sh; sh -x iso/push_to_releases.sh ' + PLATFORM + ' ' +  PROJECT + ' ' + VERSION
				copy_nessus_result(PROJECT, VERSION)
				email_send(PROJECT, VERSION)
			}
		}
		else if (PLATFORM == 'RHEL-7') {
		
			node('rhel7-iso-build-machine'){
				deleteDir()
				git url: 'git@bitbucket.org:ss8/scripts.git', branch: 'master'
				sh 'chmod +x iso/push_to_releases.sh; sh -x iso/push_to_releases.sh ' + PLATFORM + ' ' +  PROJECT + ' ' + VERSION
				copy_nessus_result(PROJECT, VERSION)
				email_send(PROJECT, VERSION)
			}
			
		}
    
}

////////////
// Methods
///////////


def email_send(PROJECT, VERSION) {

	
	def MAILING_LIST = ' '
	
	if (PROJECT == 'intellego') {
	
		MAILING_LIST = 'skrishna@ss8.com'
		//unstash "email-file"
		//emailext body: 'URL for SPL: http://10.0.135.251/releases/' + PROJECT + '/' + VERSION, subject: 'A new SPL for ' + PROJECT + ' is available', to: MAILING_LIST
		emailext mimeType: 'text/html', body: '${FILE,path="/tmp/final-email-${PROJECT}-${VERSION}.html"}', subject: 'A new SPL for ' + PROJECT + ' is available', to: MAILING_LIST

	}
	else if (PROJECT == 'sensor'){
	
		MAILING_LIST = 'skrishna@ss8.com'
		//emailext body: 'URL for SPL: http://10.0.135.251/releases/' + PROJECT + '/' + VERSION, subject: 'A new SPL for ' + PROJECT + ' is available', to: MAILING_LIST
		emailext mimeType: 'text/html', body: '${FILE,path="/tmp/final-email-${PROJECT}-${VERSION}.html"}', subject: 'A new SPL for ' + PROJECT + ' is available', to: MAILING_LIST
		
	}
}



def copy_nessus_result(PROJECT, VERSION){
		// Download artifact from Nessus scan.  10.0.165.2 is the permanent IP chosen for ISO automation
		step ([$class: 'CopyArtifact',
				projectName: 'nessus-scan-pipeline',
				filter: 'nessus-scan-10.0.165.2.html']);
		
		sh (script: "mv nessus-scan-10.0.165.2.html ${PROJECT}-spl-${VERSION}.html; scp *.html root@10.0.135.251:/var/www/html/releases/${PROJECT}/${VERSION}", returnStdout: true)
	
}

