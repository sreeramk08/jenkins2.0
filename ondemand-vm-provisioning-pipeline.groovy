
// Global declarations
def HOSTS = []
def IPS = []


// Determine what to do first

stage ('Init') {
	node('ansible'){
		echo "Checking out code..."
		git url: 'git@bitbucket.org:ss8/scripts.git', branch: 'master'
	
		def hosts = sh(script: "ansible -i ansible/create_vm_hosts ${CONFIG} --list-hosts  | grep -v hosts | sed -e 's/^[[:space:]]*//' | tr '\n' ' ' ", returnStdout: true).trim()	
		HOSTS = hosts.tokenize(" ")
		println HOSTS
		
		if (PURGE_VMS_AND_EXIT == 'true') {
			remove_vms (HOSTS)
			currentBuild.result = 'SUCCESS'
	    }
		else {
			// First check if there are enough IP addresses supplied
			IPS = IP_ADDRESSES.tokenize(",")
				
			if ( CONFIG == "intellego-single-plus-vmc") {
				// Make sure we got two IP ADDRESSES
				if ( IPS.size() != 2 ) {
					echo "Need two IP Adresses but got only " + IPS
					sh 'exit 1'
					currentBuild.result = 'FAILURE'	
						
				}
			}
		    create_vms(HOSTS)
		    networking_vms(HOSTS, IPS)
		}
	}
}

//////////////////////////////
//                          //
//   Define methods here    //
//                          //
//////////////////////////////


def remove_vms(HOSTS) {
	stage ('Deleting VMs') {
		for (int i = 0; i < HOSTS.size(); i++) {

			// Get the actual string here.
			def host = HOSTS.get(i)
			echo "Purging host: " + host
			
			node ('ansible') {
				git url: 'git@bitbucket.org:ss8/scripts.git', branch: 'master'
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

def create_vms(HOSTS){
	stage ('Creating VMs') {
	    // ** No need for a for loop
		node ('ansible') {
		    git url: 'git@bitbucket.org:ss8/scripts.git', branch: 'master'
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
					git url: 'git@bitbucket.org:ss8/scripts.git', branch: 'master'
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
