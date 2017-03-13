
currentBuild.displayName = CONFIG + '-'	+ SPL_VERSION

def HOSTS = []
def host = " "
def IPS = []
def OS_TYPE = ' '
def ISO_PATH = ' '
def PROJECT = ' '

//	All Operations will take place in this workspace. 
def WS = '/home/support/jenkins/jenkins20/workspace/Intellego/ondemand-workspace'	

timestamps {
//	This code runs on jenkins-slave-1 with ansible version 2.1.0.0.  As of this 
// 	programming, higher versions had issues when copying huge files like the intellego binaries
	node('ansible'){
		ws("${WS}"){
			deleteDir()
			git url: 'git@bitbucket.org:ss8/scripts.git', branch: 'master'   
			
			if (CONFIG == 'intellego-single-plus-vmc') {
		//	Reserve the first IP address for the vmc
				VMC_IP = FIRST_IP												
				IPS.push(VMC_IP)												
		//	constuct a vm name using the ip address.  Ex: 10.0.165.5 will give intellego-ansible-vmc-165-5									
				VMC_NAME = sh(script:"echo ${FIRST_IP} | awk -F. '{print \"intellego-ansible-vmc-\"\$3\"-\"\$4}'", returnStdout:true).trim() 
				HOSTS.push(VMC_NAME)
		//	The Intellego IP is the next IP address to the First IP provided and vm name is constructed using the IP
				INT_IP = sh(script: "echo ${FIRST_IP} | awk -F. '{print \$1\".\"\$2\".\"\$3\".\"\$4+1}'", returnStdout:true).trim()
				IPS.push(INT_IP)
		//	Ex: intellego-ansible-single-165-6
        	    INT_NAME = sh(script:"echo ${INT_IP} | awk -F. '{print \"intellego-ansible-single-\"\$3\"-\"\$4}'", returnStdout:true).trim() 
				HOSTS.push(INT_NAME)
				
				echo "INFO: HOSTS = " + HOSTS
				echo "INFO: IPS = " + IPS
				
		//	Create the contents of the hosts file with IP address and ansible password which playbooks will use throughout
		//	for all operations.  Note:  This hosts file is different than the one used by the create_vms block
				create_hosts_file(IPS, WS)	
			}	
		
		//	Purge the vm's		
			purge_vms (HOSTS, WS)   										
		//	create the vm's afresh			
			create_vms (IPS, HOSTS, SPL_VERSION, WS) 						
		//	Since Intellego install script will remove the jdk and reinstall, we need a standalone one for connecting to Jenkins if necessary
			deploy_jdk (IPS, WS)											
		//	The single and single-master.properties file
			deploy_properties (VMC_IP, INT_IP, WS)							
		//	Prepare the vm's by installing the intellego, HMP and VMC 
			deploy_and_install_binaries(VMC_IP, INT_IP, WS)					
		//	Installing the intellego licenses	
			install_intellego_license(IPS, WS)								
		//	final reboot for mounting and telnet localhost availability
			final_reboot(WS)
			send_confirmation (INT_IP, VMC_IP)		
		//	Run a basic sanity 						
			test_configuration(INT_IP, VMC_IP)
		//	Notification
			email_notification(INT_NAME, INT_IP, VMC_NAME, VMC_IP)
	    }
	}//	node
}//	timestamps

														//////////////////////////////
														//                          //
														//         Methods          //
														//                          //
														//////////////////////////////

def email_notification(INT_NAME, INT_IP, VMC_NAME, VMC_IP){
	emailext subject: 'READY: Single + VMC Pair. ' + INT_IP + ' + ' + VMC_IP, to: MAILING_LIST
}

def create_hosts_file(IPS, WS){
	sh (script: "cd ${WS}; > ansible/hosts")
	for (i = 0; i < IPS.size(); i ++){
		IP = IPS.get(i)
		sh (script: "cd ${WS}; echo ${IP} ansible_password=ss8inc >> ansible/hosts")
	}
	
}

def final_reboot(WS){
	stage('Final Reboot'){
		sh (script:"cd ${WS}; ansible-playbook -i ansible/hosts ansible/post_installation.yaml  -c paramiko --tags reboot ", returnStdout: true)
	}
}

def send_confirmation(INT_IP, VMC_IP){
	stage('Waiting for VMC License'){
		timeout(time: 2, unit: 'DAYS'){
			node ('master') {
				mail (to: MAILING_LIST,
					subject: "VMC ${VMC_IP} need licensing. ",
					body: "Click Proceed once Licensing is done ${env.BUILD_URL}/input");
					input 'Have the vms been Licensed?';
			}
		}
	}
}

def test_configuration(INT_IP, VMC_IP){
	stage('Testing'){
		node('jenkins-slave-1'){
			sh 'mv /home/support/jenkins/vms_available /home/support/jenkins/vms_available.saved'
			sh (script: "echo ${INT_IP}  ${VMC_IP} > /home/support/jenkins/vms_available")
			build job: 'ondemand-vm-testing', parameters: [[$class: 'StringParameterValue', name: 'MAILING_LIST', value: MAILING_LIST ]]
			sh 'mv /home/support/jenkins/vms_available.saved /home/support/jenkins/vms_available'
		}
	}
}

def deploy_files(IP, FILE, WS){
	echo "INFO: Deploying " + FILE + ' to ' + IP
	sh (script:"cd ${WS}; ansible-playbook -i ansible/hosts ansible/post_installation.yaml \
        -c paramiko --limit ${IP} --tags file_copy --extra-vars file=$FILE ", returnStdout: true)
}

def deploy_jdk(IPS, WS){
	stage('JDK Install'){
		FILE = sh (script:"cd ${WS} ; realpath /firebird/jdk/jdk*", returnStdout: true).trim()
		sh (script:"cd ${WS}; ansible-playbook -i ansible/hosts ansible/post_installation.yaml \
			-c paramiko --tags cleanup,file_copy,jenkins_dir,install_jdk --extra-vars file=$FILE", returnStdout: true)
	}
}

def run_ansible_tags (IP, TAGS, WS){
	sh (script:"cd ${WS}; echo ${IP} ansible_password=ss8inc > ansible/hosts; \
		ansible-playbook -i ansible/hosts ansible/post_installation.yaml  -c paramiko --tags ${TAGS} ", returnStdout: true)
}

def deploy_and_install_binaries(VMC_IP, INT_IP, WS){
	stage('Installing Binaries'){
	//	firebird/SWDrops is cifs mounted onto the jenkins-slave-1 vm. We grab Intellego and other binaries using the Release version specified in the job
	
	parallel 'intellego': {
	//	Deploy and install intellego on both vm's 
		FILE = sh (script:"cd ${WS} ; realpath /firebird/SWDrops/Release${RELEASE_VERSION}/intellego/SS8*.bin", returnStdout: true).trim()
		sh (script:"cd ${WS}; ansible-playbook -i ansible/hosts ansible/post_installation.yaml \
				-c paramiko --tags file_copy,install_intellego,enable_rtus,ntpdate --extra-vars file=$FILE ", returnStdout: true)

	//	Next, copy the datawipe configuration files as well
		FILE = sh (script:"cd ${WS} ; realpath ansible/intellego_files/batch.properties", returnStdout: true).trim()
		sh (script:"cd ${WS}; ansible-playbook -i ansible/hosts ansible/post_installation.yaml \
			-c paramiko --tags file_copy --extra-vars file=$FILE ", returnStdout: true)
		FILE = sh (script:"cd ${WS} ; realpath ansible/intellego_files/retention.properties", returnStdout: true).trim()
		sh (script:"cd ${WS}; ansible-playbook -i ansible/hosts ansible/post_installation.yaml \
			-c paramiko --tags file_copy --extra-vars file=$FILE ", returnStdout: true)

	//	Move the datawipe properties files to the correct location
		sh (script:"cd ${WS}; ansible-playbook -i ansible/hosts ansible/post_installation.yaml -c paramiko --tags datawipe_properties", returnStdout: true)
		sh (script:"cd ${WS}; ansible-playbook -i ansible/hosts ansible/post_installation.yaml -c paramiko --limit ${VMC_IP} --tags cidformat", returnStdout: true)
		
		},
	//	Hard requirement that HMP needs to be installed before VMC
		'vmc HMP+VMC': {
			FILE = sh (script:"cd ${WS} ; realpath /firebird/SWDrops/Release${RELEASE_VERSION}/VMC/Dialogic/hmp*.tar.gz", returnStdout: true).trim()
			sh (script:"cd ${WS}; ansible-playbook -i ansible/hosts ansible/post_installation.yaml --limit ${VMC_IP} \
				-c paramiko --tags file_copy,extract_hmp,install_hmp --extra-vars file=$FILE ", returnStdout: true)
		
			FILE = sh (script:"cd ${WS} ; realpath /firebird/SWDrops/Release${RELEASE_VERSION}/VMC/VMC*/VMC-installer*.bin", returnStdout: true).trim()
			sh (script:"cd ${WS}; ansible-playbook -i ansible/hosts ansible/post_installation.yaml --limit ${VMC_IP} \
				-c paramiko --tags file_copy,install_vmc,ntpdate --extra-vars file=$FILE ", returnStdout: true)

	//	Mount the contentvmc directory after
			sh (script:"cd ${WS}; ansible-playbook -i ansible/hosts ansible/post_installation.yaml -c paramiko --tags mount_contentvmc", returnStdout: true)
		}
	}
		
}
	
def install_intellego_license(IPS, WS){
	stage('Licensing Intellego'){
		for (i=0; i<IPS.size(); i++){
			IP = IPS.get(i)
			node('jenkins-slave-2'){
				sh (script: "cd  /home/support/installego; sudo -u support python installego.py --license ${IP}", returnStdout: true)
			}
		}
	}
}													
																												
def deploy_properties (VMC_IP, INT_IP, WS){		
	if ( CONFIG == "intellego-single-plus-vmc") {  			
	//	Remove the hosts from the known_hosts as ansible complains
		sh (script:"ssh-keygen -R ${VMC_IP}",returnStdout: true)  					
		sh (script:"ssh-keygen -R ${INT_IP}",returnStdout: true)
			
	//	VMC Handling
	// 	Update the properties files with the ip addresses
		sh (script: "cd ${WS} ; sed -i.bak -e 's/VMC_IPADDRESS/${VMC_IP}/g' \
                      -e 's/IPADDRESS/${INT_IP}/g' ansible/intellego_files/vmc-install.properties", returnStdout: true)
	// 	Push the properties files 
		FILE = sh (script:"cd ${WS} ; realpath ansible/intellego_files/vmc-install.properties", returnStdout: true).trim()
		deploy_files(VMC_IP, FILE, WS)
	//	Rename the properties file to single and single-master.properties
		sh (script:"cd ${WS}; ansible-playbook -i ansible/hosts ansible/post_installation.yaml \
               -c paramiko --limit ${VMC_IP} --tags single_properties --extra-vars file=$FILE ", returnStdout: true)

	//	Intellego handling
		sh (script: "cd ${WS} ; sed -i.bak -e 's/VMC_IPADDRESS/${VMC_IP}/g' \
				-e 's/IPADDRESS/${INT_IP}/g' ansible/intellego_files/full-install.properties", returnStdout: true)
		
		FILE = sh (script:"cd ${WS} ; realpath ansible/intellego_files/full-install.properties", returnStdout: true).trim()
		deploy_files(INT_IP, FILE, WS)
			
	//	Rename the properties file to single and single-master.properties for possible CI usage
		sh (script:"cd ${WS}; ansible-playbook -i ansible/hosts ansible/post_installation.yaml \
			-c paramiko --limit ${INT_IP} --tags single_properties --extra-vars file=$FILE ", returnStdout: true)

	//	Create the /NAS/contentvmc folder and make the changes in fstab on the intellego vm
		sh (script: "cd ${WS} ; ansible-playbook -i ansible/hosts ansible/post_installation.yaml \
			-c paramiko --limit ${INT_IP} --tags create_nas_folder,modify_fstab --extra-vars vmc_ip=${VMC_IP}", returnStdout: true)
	}
}

def purge_vms(HOSTS, WS) {
	stage ('Purging VMs') {
		// Create a hosts file just for dealing with the vms in Ansible.
		sh (script: "cd ${WS}; echo [${CONFIG}] > ansible/ansible_hosts")
		for (i = 0; i < HOSTS.size(); i ++){
			NAME = HOSTS.get(i)
			sh (script: "cd ${WS}; echo ${NAME} >> ansible/ansible_hosts")
		}
		
		for (int i = 0 ; i < HOSTS.size(); i++) {
			// Get the actual string here.
			host = HOSTS.get(i)
			echo "Purging host: " + host
			
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
				} 
			} 
		}
	}
}

def get_iso_path(SPL_VERSION, CONFIG){
	
	def PROJ = sh(script:"echo ${CONFIG} | awk -F- '{print \$1}' ", returnStdout:true).trim()
	ISO_PATH = 'ISO Datastore/SPL/' + PROJ + '-spl-' + SPL_VERSION + '.iso'
	echo "INFO: ISO_PATH = " + ISO_PATH
	
}

def create_vms(IPS, HOSTS, SPL_VERSION, WS){
	stage ('VM Creation') {
	    // ** No need for a for loop
		node ('ansible') {
			//	Determine OS Type based on configuration chosen
			if	(CONFIG =~ "intellego*") {
				OS_TYPE = "rhel6_64Guest"
				echo "INFO: OS_TYPE = " + OS_TYPE
			}
			else {
				OS_TYPE = "rhel7_64Guest"
				echo "INFO: OS_TYPE = " + OS_TYPE
			}
			//	ISO PATH Determination
			get_iso_path(SPL_VERSION, CONFIG)										
			
			// Create a hosts file just for creating the vms in Ansible.
			sh (script: "cd ${WS}; echo [${CONFIG}] > ansible/ansible_hosts")
			for (i = 0; i < HOSTS.size(); i ++){
				NAME = HOSTS.get(i)
				sh (script: "cd ${WS}; echo ${NAME} >> ansible/ansible_hosts")
			}
		    
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
				
			// Force sleep for a couple more minutes because by experience, the vms are not yet ready
			sh 'sleep 120'
			
			networking_vms(HOSTS, IPS, WS)
		} //node
	}
}

def networking_vms(HOSTS, IPS, WS){
	stage( 'Networking' ){
		for( int i = 0; i < HOSTS.size(); i++ ){
			host = HOSTS.get(i)
			ip_t = IPS.get(i)
			echo "INFO: Host = " + host + " IP = " + ip_t
				node ('ansible') {
					//git url: 'git@bitbucket.org:ss8/scripts.git', branch: 'master'
					withCredentials([[$class: 'UsernamePasswordMultiBinding', \
						credentialsId: '9c58604a-d576-45ab-89e3-1aa80b5cecdb', \
						passwordVariable: 'VCENTER_PASS', usernameVariable: 'VCENTER_USER']]) {
						sh 'cd ' + WS + '; /usr/bin/ansible-playbook -i ansible/ansible_hosts  ansible/modify_vms.yaml \
							--limit=' + host + ' --extra-vars "project=' + CONFIG + ' vm_id=' + host + \
							' vcenter_hostname=' + VCENTER + ' vcenter_user=' + VCENTER_USER + \
							' vcenter_pass=' + VCENTER_PASS  + ' datacenter=' + DATACENTER + \
							' ip_address=' + ip_t + ' gateway=' + GATEWAY + '"'
					} // withcreds
				} //node
		}
	}
}


