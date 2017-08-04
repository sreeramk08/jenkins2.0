#!groovy

currentBuild.displayName = PROJECT + '-' + VERSION	

stage('Release ISO'){

	
		if (PLATFORM == 'RHEL-6') {
				
			node('rhel6-spl-build-vm-165-31'){
				deleteDir()
				git url: 'git@bitbucket.org:ss8/scripts.git', branch: 'master'
				sh 'chmod +x iso/push_to_releases.sh; sh -x iso/push_to_releases.sh ' + PLATFORM + ' ' +  PROJECT + ' ' + VERSION
				copy_nessus_result(PROJECT, VERSION)
				email_send(PROJECT, VERSION)
			}
		}
		else if (PLATFORM == 'RHEL-7') {
		
			node('rhel7-spl-build-vm-165-29'){
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
		emailext mimeType: 'text/html', body: '${FILE,path="/tmp/final-email-${PROJECT}-${VERSION}.html"}', subject: 'New SPL Version ' + VERSION + ' for ' + PROJECT + ' is available', to: MAILING_LIST

	}
	else if (PROJECT == 'sensor'){
	
		MAILING_LIST = 'skrishna@ss8.com'
		emailext mimeType: 'text/html', body: '${FILE,path="/tmp/final-email-${PROJECT}-${VERSION}.html"}', subject: 'New SPL Version ' + VERSION + ' for ' + PROJECT + ' is available', to: MAILING_LIST
		
	}
	
	else if (PROJECT == 'discovery'){
	
		MAILING_LIST = 'skrishna@ss8.com'
		emailext mimeType: 'text/html', body: '${FILE,path="/tmp/final-email-${PROJECT}-${VERSION}.html"}', subject: 'New SPL Version ' + VERSION + ' for ' + PROJECT + ' is available', to: MAILING_LIST
		
	}
	else if (PROJECT == 'security-analytics'){
	
		MAILING_LIST = 'skrishna@ss8.com'
		emailext mimeType: 'text/html', body: '${FILE,path="/tmp/final-email-${PROJECT}-${VERSION}.html"}', subject: 'New SPL Version ' + VERSION + ' for ' + PROJECT + ' is available', to: MAILING_LIST
		
	}
	
	else if (PROJECT == 'xcipio'){
		
		MAILING_LIST = 'skrishna@ss8.com'
		emailext mimeType: 'text/html', body: '${FILE,path="/tmp/final-email-${PROJECT}-${VERSION}.html"}', subject: 'New SPL Version ' + VERSION + ' for ' + PROJECT + ' is available', to: MAILING_LIST
			
	}
	
}



def copy_nessus_result(PROJECT, VERSION){
		// Download artifact from Nessus scan.  10.0.165.2 is the permanent IP chosen for ISO automation
		step ([$class: 'CopyArtifact',
				projectName: 'nessus-scan-pipeline',
				filter: 'nessus-scan-10.0.165.2.html']);
		
		sh (script: "mv nessus-scan-10.0.165.2.html ${PROJECT}-spl-${VERSION}.html; scp *.html root@10.0.135.251:/var/www/html/releases/${PROJECT}/${PLATFORM}-${VERSION}", returnStdout: true)
	
}

