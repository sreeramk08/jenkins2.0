
// Global declarations
def HOSTS = []
def IPS = []
def WS = '/home/support/jenkins/jenkins20/workspace/Intellego/ondemand-workspace'


// ############## Initialize #################

stage ('Init') {
	node('ansible'){
		ws ("${WS}") {
			deleteDir()
			// &&&&&&&&&& Directory that will be used throughout the build &&&&&&&&&&&&&&
			git url: 'git@bitbucket.org:ss8/scripts.git', branch: 'master'
				
			def hosts = sh(script: "ansible -i ansible/create_vm_hosts ${CONFIG} --list-hosts  | grep -v hosts | sed -e 's/^[[:space:]]*//' | tr '\n' ' ' ", returnStdout: true).trim()	
			HOSTS = hosts.tokenize(" ")
			echo "Working on " + HOSTS
		
			if (PURGE_VMS_AND_EXIT == 'true') {
				remove_vms (HOSTS)
				currentBuild.result = 'SUCCESS'
			}
			else {
				// First check if there are enough IP addresses supplied
				IPS = IP_ADDRESSES.tokenize(", ")
				
				if ( CONFIG == "intellego-single-plus-vmc") {
					// Make sure we got two IP ADDRESSES
					if ( IPS.size() != 2 ) {
						echo "Need two IP Adresses but got only " + IPS
						sh 'exit 1'
						currentBuild.result = 'FAILURE'	
					}
				
				}
				//create_vms(HOSTS)
				//networking_vms(HOSTS, IPS)
				
				set_hostnames(IP_ADDRESSES, WS) 
		
				get_binary_to_install(BINARY_VERSION, WS)
	    
				binary_installation(IPS, WS)
				
			}
		}
	}
}





														//////////////////////////////
														//                          //
														//         Methods          //
														//                          //
														//////////////////////////////


// ########### Set hostnames #################
def set_hostnames(IP_ADDRESSES, WS){
	
	echo "*********** set_hostnames ************"		
	sh (script: "cd ${WS} ; ansible/hosts_file.sh ${IP_ADDRESSES} ; ansible-playbook -i ansible/hosts ansible/post_installation.yaml --tags set_hostname", returnStdout: true)
	
}														
														
														
// ########### Installation #################

def binary_installation(IPS, WS){
	stage ('Installing Binary'){
		
		echo "*********** binary_installation ************"
		if ( CONFIG == "intellego-single-plus-vmc") {  
			//git url: 'git@bitbucket.org:ss8/scripts.git', branch: 'master'
			sh 'pwd'
			// 1st IP is for VMC and second is for Intellego
			def vmc_ip = IPS[0]
			def int_ip = IPS[1]
		
			// &&&&&&&& VMC INSTALLATION &&&&&&&&&&
			// Update the properties files with the ip addresses
			sh (script: "cd ${WS} ; echo ${vmc_ip} ansible_password=ss8inc > ansible/hosts; sed -i.bak -e 's/VMC_IPADDRESS/${vmc_ip}/g' \
				-e 's/IPADDRESS/${int_ip}/g' ansible/intellego_files/vmc-install.properties", returnStdout: true)
				
			// Push the properties files 
			FILE = sh (script:"cd ${WS} ; realpath ansible/intellego_files/vmc-install.properties", returnStdout: true).trim()
			deploy_files(FILE, WS)
			
			sh (script: "cd ${WS} ; ansible-playbook -i ansible/hosts ansible/post_installation.yaml --tags install_intellego", returnStdout: true)
			
			// &&&&&&&&&&& FULL Intellego Installation &&&&&&&&&&&&&
			sh (script: "cd ${WS} ; echo ${int_ip} ansible_password=ss8inc > ansible/hosts; sed -i.bak -e 's/VMC_IPADDRESS/${vmc_ip}/g' \
				-e 's/IPADDRESS/${int_ip}/g' ansible/intellego_files/full-install.properties", returnStdout: true)
		
			FILE = sh (script:"cd ${WS} ; realpath ansible/intellego_files/full-install.properties", returnStdout: true).trim()
			deploy_files(FILE, WS)
					
			sh (script: "cd ${WS} ; ansible-playbook -i ansible/hosts ansible/post_installation.yaml --tags install_intellego", returnStdout: true)

		}
		
	} // stage end
	
}


// ##################  Download the binary from the official-build-machine  ############################

def get_binary_to_install(BINARY_VERSION, WS){
	stage( 'Getting Binary' ){
		echo "*********** get_binary_to_install ************"
		node('intellego-official-build-machine'){
			PATH_TO_BIN = sh(script: "find /intellego/bin -type d -name ${BINARY_VERSION}.* -maxdepth 3 | grep intelbld | sort | tail -1", returnStdout: true)
			echo "Got the path to binary as: " + PATH_TO_BIN
			ws ("${PATH_TO_BIN}"){
			   stash name: "binary", includes: "*.bin" 
			}
		}
		node('ansible'){
			
				unstash "binary"
				// ******* Deploy binary to the vms *******
				sh 'pwd'
				// Cleanup /SS8 directory
				sh (script:"cd ${WS}; ansible-playbook -i ansible/hosts ansible/post_installation.yaml --tags cleanup", returnStdout: true)
				// Prepare to copy binary
				BINARY = sh (script:"realpath *.bin", returnStdout: true).trim()
				echo "Binary path: " + BINARY
				deploy_files(BINARY, WS)
			
		}
	}
}


def deploy_files(FILE, WS){
	
	echo "*********** deploy_files ************"
	//git url: 'git@bitbucket.org:ss8/scripts.git', branch: 'master'
	//sh 'echo > ansible/hosts; for IP in `echo $IP_ADDRESSES | sed "s/,/ /g"`; do echo $IP ansible_password=ss8inc >> ansible/hosts ; done'
	echo "Deploying " + FILE + ' to ' + IP_ADDRESSES
	echo "Got WS as: " + WS
	sh 'pwd'
	sh (script:"cd ${WS}; ansible-playbook -i ansible/hosts ansible/post_installation.yaml --tags file_copy --extra-vars file=$FILE ", returnStdout: true)

	
}

// ##################### Remove the vms ###############################

def remove_vms(HOSTS) {
	stage ('Deleting VMs') {
		for (int i = 0; i < HOSTS.size(); i++) {

			// Get the actual string here.
			def host = HOSTS.get(i)
			echo "Purging host: " + host
			
			node ('ansible') {
				//git url: 'git@bitbucket.org:ss8/scripts.git', branch: 'master'
				withCredentials([[$class: 'UsernamePasswordMultiBinding', \
					credentialsId: '9c58604a-d576-45ab-89e3-1aa80b5cecdb', \
					passwordVariable: 'VCENTER_PASS', usernameVariable: 'VCENTER_USER']]) {
                    try{
					sh '/usr/bin/ansible-playbook -i ansible/create_vm_hosts ansible/remove_vms.yaml \
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
}

// Creation of the vms

def create_vms(HOSTS){
	stage ('Creating VMs') {
	    // ** No need for a for loop
		node ('ansible') {
		    //git url: 'git@bitbucket.org:ss8/scripts.git', branch: 'master'
			withCredentials([[$class: 'UsernamePasswordMultiBinding', \
				credentialsId: '9c58604a-d576-45ab-89e3-1aa80b5cecdb', \
				passwordVariable: 'VCENTER_PASS', usernameVariable: 'VCENTER_USER']]) {
   					sh '/usr/bin/ansible-playbook -i ansible/create_vm_hosts ansible/create_vms.yaml \
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

def networking_vms(HOSTS, IPS){
	stage( 'Networking VMs' ){
		for( int i = 0; i < HOSTS.size(); i++ ){
			def host = HOSTS.get(i)
			echo "Working on host: " + host
				//The IP address given can have leading or trailing spaces
				def ip_t = IPS.get(i) 
				ip = sh(script: "echo $ip_t | sed 's/ //g'", returnStdout: true)
				echo "Using IP: " + ip
				node ('ansible') {
					//git url: 'git@bitbucket.org:ss8/scripts.git', branch: 'master'
					withCredentials([[$class: 'UsernamePasswordMultiBinding', \
						credentialsId: '9c58604a-d576-45ab-89e3-1aa80b5cecdb', \
						passwordVariable: 'VCENTER_PASS', usernameVariable: 'VCENTER_USER']]) {
						sh '/usr/bin/ansible-playbook -i ansible/create_vm_hosts  ansible/modify_vms.yaml \
							--limit=' + host + ' --extra-vars "project=' + CONFIG + ' vm_id=' + host + \
							' vcenter_hostname=' + VCENTER + ' vcenter_user=' + VCENTER_USER + \
							' vcenter_pass=' + VCENTER_PASS  + ' datacenter=' + DATACENTER + \
							' ip_address=' + ip + '"'
					} // withcreds
				} //node
		}
	}
}


