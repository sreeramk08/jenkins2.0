#!groovy

currentBuild.displayName = PROJECT + '-' + VERSION


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
								
				// 	Iso creation was successful
					if (exists) {
					    echo "Copying to ISO Datastore"
						echo "ISO's are available! Renaming them..."
						def ISO = sh(script:"cd dist; ls *.iso | grep -v upgrade", returnStdout: true).trim()
						sh 'sudo mv dist/' + ISO + ' dist/' + PROJECT + '-spl-testing-' + VERSION + '.iso'
					//	Only Intellego has an upgrade ISO
						if ( PROJECT == 'intellego') {
							def UP_ISO = sh(script:"cd dist; ls *.iso | grep upgrade", returnStdout: true).trim()
							sh 'sudo mv dist/' + UP_ISO + ' dist/' + PROJECT + '-spl-testing-' + VERSION + '-upgrade.iso'
						}
						echo "Copying to ISO Datastore"
						sh 'scp dist/*.iso isoadmin@10.0.155.54:/ISOFolder/SPL'
						
					// 	Test the SPL created
						build job: '2-Test-SPL', parameters: [[$class: 'StringParameterValue', name: 'CONFIG', value: PROJECT + '-spl'], \
						           [$class: 'StringParameterValue', name: 'SPL_VERSION', value: VERSION ], \
								   [$class: 'StringParameterValue', name: 'PLATFORM', value: PLATFORM ], \
								   [$class: 'StringParameterValue', name: 'MAILING_LIST', value: MAILING_LIST ]]
								   

					} 
					else {
						emailext body: 'ISO Creation unsuccessful!', attachLog: true, subject: 'ISO Creation unSuccessfull', to: MAILING_LIST
						currentBuild.result = 'FAILURE'
						throw err
					}
			} // ws block
		} // end of node
	
	}
	else if (PLATFORM == 'RHEL-7' ) {
	
		node ('rhel7-iso-build-machine'){
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
					sh 'sudo mv dist/' + ISO + ' dist/' + PROJECT + '-spl-testing-rhel7-' + VERSION + '.iso'
					// Upgrade iso is built only for intellego as of now
					if ( PROJECT == 'intellego') {
						def UP_ISO = sh(script:"cd dist; ls *.iso | grep upgrade", returnStdout: true).trim()
						sh 'sudo mv dist/' + UP_ISO + ' dist/' + PROJECT + '-spl-testing-rhel7-' + VERSION + '-upgrade.iso'
					}
					echo "Copying renamed SPL's to ISO Datastore"
					sh 'scp dist/*.iso isoadmin@10.0.155.54:/ISOFolder/SPL'
					
					// Test the SPL created
					build job: '2-Test-SPL', parameters: [[$class: 'StringParameterValue', name: 'CONFIG', value: PROJECT + '-spl'], \
					       [$class: 'StringParameterValue', name: 'SPL_VERSION', value: VERSION ], \
						   [$class: 'StringParameterValue', name: 'PLATFORM', value: PLATFORM ], \
						   [$class: 'StringParameterValue', name: 'MAILING_LIST', value: MAILING_LIST ]]
								   
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



