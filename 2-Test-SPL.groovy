
currentBuild.displayName = CONFIG + '-' + SPL_VERSION	


// Global declarations

def WS = '/home/support/jenkins/jenkins20/workspace/Intellego/test-spl-workspace'
def OS_TYPE = ' '
def ISO_PATH = ' '
def PROJECT = ' '

// ############## Initialize #################

stage ('Creating VM') {
	node('ansible'){
		ws ("${WS}") {
			deleteDir()
			git url: 'git@bitbucket.org:ss8/scripts.git', branch: 'master'
				
		//	Host name is predefined in the ansible_hosts file. 
			def host = sh(script: "ansible -i ansible/ansible_hosts ${CONFIG} --list-hosts  | grep -v hosts | sed -e 's/^[[:space:]]*//' | tr '\n' ' ' ", returnStdout: true).trim()	
			
		//	Only intellego as of now uses RHEL6.  The rest use RHEL-7	
		    if (CONFIG == "intellego-spl") {
				OS_TYPE = "rhel6_64Guest"
			}
			else {
			    OS_TYPE = "rhel7_64Guest"
			}
		//	Clean up first.  Remove first and then re-create.  handle xcipio spl differently. 
			if (CONFIG != "xcipio-spl"){
				remove_vms (host, WS)
			}
		//	Create fresh after removing in previous step
			create_vms(host, WS, OS_TYPE, SPL_VERSION)
	
			networking_vms(host, IP_ADDRESS, WS)
			
		//	For Sensor, we also need to collect the rpmdb from the vm 
			if (CONFIG == 'sensor-spl'){
				collect_rpmdb(SPL_VERSION,IP_ADDRESS, WS)
			}			
		}
	}
}

stage ('Nessus Scan'){
	// Nessus scan
	node('ansible'){		
	
			build job: 'nessus-scan-pipeline', parameters: [[$class: 'StringParameterValue', name: 'TARGETS', value: IP_ADDRESS], [$class: 'StringParameterValue', name: 'MAILING_LIST', value: MAILING_LIST ]]
			step ([$class: 'CopyArtifact',
				projectName: 'nessus-scan-pipeline',
				filter: '*.html']);
			
			stash name: "nessus-scan", includes: "*.html"
			
			ws ("${WS}") {
				// Determine which vm to remove
				def host = sh(script: "ansible -i ansible/ansible_hosts ${CONFIG} --list-hosts  | grep -v hosts | sed -e 's/^[[:space:]]*//' | tr '\n' ' ' ", returnStdout: true).trim()
				// Cleanup after nessus scan
				remove_vms (host, WS)
			}
	}			
}


// Nessus scan job above will send an email.  Based on the email, a decision needs to be taken. 

timeout(time: 2, unit: 'DAYS'){

	node ('master') {
    
		mail (to: MAILING_LIST,
			//subject: "Job '${env.JOB_NAME}' (${env.BUILD_NUMBER}) is waiting for input",
			subject: "${CONFIG} is waiting for promotion.",
			body: "Promotion Decision link:  ${env.BUILD_URL}/input");
		input 'Promote the ISO?';
		
		// Promote the SPL created
		PROJECT = sh( script: "echo ${CONFIG} | awk -F'-spl' '{print \$1}'", returnStdout: true).trim()
		
		build job: '3-Promote-SPL', parameters: [[$class: 'StringParameterValue', name: 'PROJECT', value: PROJECT ], \
		           [$class: 'StringParameterValue', name: 'VERSION', value: SPL_VERSION ],\
				   [$class: 'StringParameterValue', name: 'PLATFORM', value: PLATFORM ],\
		           [$class: 'StringParameterValue', name: 'MAILING_LIST', value: MAILING_LIST ]]
   
	}
}


														//////////////////////////////
														//                          //
														//         Methods          //
														//                          //
														//////////////////////////////



def collect_rpmdb(SPL_VERSION,IP_ADDRESS, WS){
	node('ansible'){
		//	This step wlll fetch the rpmdb from the vm created and put it in the jenkins-slave-1
		sh 'cd ' + WS + ' ; echo ${IP_ADDRESS} ansible_password=ss8inc > ansible/hosts; \
                    ansible-playbook -i ansible/hosts ansible/sensor_rpmdb.yaml \
		            -c paramiko --tags create_rpmdb,fetch_rpmdb --extra-vars "SPL_VERSION=' + SPL_VERSION + '"'
        //	Now push it to one of the ISO Machines
		if (PLATFORM == 'RHEL-6'){
			//	Push the rpmdb to the RHEL-6 vm
			sh 'cd ' + WS + ' ; echo 10.0.225.170 ansible_password=' + ISO_BUILD_USER_PASSWD + ' ansible_user=iso-build-user > ansible/hosts; \
			ansible-playbook -i ansible/hosts ansible/sensor_rpmdb.yaml \
			-c paramiko --tags copy_rpmdb --extra-vars "SPL_VERSION=' + SPL_VERSION + ' PLATFORM=' + PLATFORM + '"'
		}
		else {
			//	Push the rpmdb to the RHEL-7 vm
			sh 'cd ' + WS + ' ; echo 10.0.225.171 ansible_password=' + ISO_BUILD_USER_PASSWD + ' ansible_user=iso-build-user > ansible/hosts; \
			ansible-playbook -i ansible/hosts ansible/sensor_rpmdb.yaml \
			-c paramiko --tags copy_rpmdb --extra-vars "SPL_VERSION=' + SPL_VERSION + ' PLATFORM=' + PLATFORM + '"'
			node('rhel7-iso-build-machine'){
				ws('/home/iso-build-user/ISO_BUILD/platform-isos/RHEL-7/addons/devel-addon'){
					sh 'sudo gmake ci VERSION=' + SPL_VERSION
				}
			}
			
		}
	}
	
}

def remove_vms(host, WS) {
	stage ('Purging VM') {
		
			node ('ansible') {
				
				withCredentials([[$class: 'UsernamePasswordMultiBinding', \
					credentialsId: '9c58604a-d576-45ab-89e3-1aa80b5cecdb', \
					passwordVariable: 'VCENTER_PASS', usernameVariable: 'VCENTER_USER']]) {
                    try{
					sh 'cd ' + WS + '; /usr/bin/ansible-playbook -i ansible/ansible_hosts ansible/remove_vms.yaml \
						--limit=' + host + ' --extra-vars "vcenter_hostname=' + VCENTER + ' vcenter_user=' + VCENTER_USER + \
						' vcenter_pass=' + VCENTER_PASS + ' remove_vm=' + host + ' project=' + CONFIG + \
						' esxi_host=' + ESX_HOST + ' datacenter=' + DATACENTER + '"'
                    }
                    catch(err){
                        currentBuild.result = 'SUCCESS'
                    }
				} // withcreds
			} //node
		
	}
}

// Creation of the vms

def create_vms(host, WS, OS_TYPE, SPL_VERSION){
	stage ('Creating VM') {
		
		// Get the name of the iso based on the project and version.  Either the rhel6 or rhel7 machine can be used
		node('rhel6-iso-build-machine'){
			
			PROJECT = sh( script: "echo ${CONFIG} | awk -F- '{print \$1}'", returnStdout: true).trim()
			def ISO_NAME = sh (script: "ssh isoadmin@10.0.155.54 \"ls /ISOFolder/SPL | grep ${PROJECT}  | grep ${SPL_VERSION} | grep -v upgrade\" ", returnStdout: true ).trim()
			ISO_PATH = 'ISO Datastore/SPL/' + ISO_NAME
			
		}
		
		node ('ansible') {
			withCredentials([[$class: 'UsernamePasswordMultiBinding', \
				credentialsId: '9c58604a-d576-45ab-89e3-1aa80b5cecdb', \
				passwordVariable: 'VCENTER_PASS', usernameVariable: 'VCENTER_USER']]) {
   					sh 'cd ' + WS + '; /usr/bin/ansible-playbook -i ansible/ansible_hosts ansible/create_vms.yaml \
						--extra-vars "project=' + CONFIG + \
						' vcenter_hostname=' + VCENTER + ' vcenter_user=' + VCENTER_USER + \
						' vcenter_pass=' + VCENTER_PASS + ' osid=' + OS_TYPE + \
						' iso_path=' + '\'' + ISO_PATH  + '\'' + ' datastore=' + '\'' + DATASTORE + '\'' + \
						' esxi_host=' + ESX_HOST + ' datacenter=' + DATACENTER + \
						' network=' + '\'' + NETWORK + '\'' + '"'
			} // withcreds
				
			// Force sleep for a couple more minutes because they are not ready yet
			sh 'sleep 120'
			
		} //node
	}
}

// Networking the vms created

def networking_vms(host, IP_ADDRESS, WS){
	stage( 'Networking VM' ){
		echo "Working on host: " + host
		node ('ansible') {
			withCredentials([[$class: 'UsernamePasswordMultiBinding', \
			credentialsId: '9c58604a-d576-45ab-89e3-1aa80b5cecdb', \
			passwordVariable: 'VCENTER_PASS', usernameVariable: 'VCENTER_USER']]) {
			sh 'cd ' + WS + '; /usr/bin/ansible-playbook -i ansible/ansible_hosts  ansible/modify_vms.yaml \
				--limit=' + host + ' --extra-vars "project=' + CONFIG + ' vm_id=' + host + \
							' vcenter_hostname=' + VCENTER + ' vcenter_user=' + VCENTER_USER + \
							' vcenter_pass=' + VCENTER_PASS  + ' datacenter=' + DATACENTER + \
							' ip_address=' + IP_ADDRESS + ' gateway=' + GATEWAY + '"'
					} // withcreds
			
		} //node
		
	}
}

		
		
