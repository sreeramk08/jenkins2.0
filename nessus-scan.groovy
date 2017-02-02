
// Get the list of targets
def targets = TARGETS.tokenize(",")

def parallelstep(inputString) {
    return {
       node ('jenkins-slave') {
          stage(inputString) {
             echo "Working on :" + inputString
             // call the gradle block here
             run_scan(inputString)
          } //stage block
       } // node block
    } //return block
}

def run_scan(target){
	node ('jenkins-slave') {
		// source code from git
		deleteDir()
        git url: 'git@bitbucket.org:ss8/scripts.git', branch: 'master'
		sh 'chmod +x nessus/custom-scan.sh; nessus/custom-scan.sh ' + target
		archive '*.txt, *.html'
		stash name: target, includes: "*.txt, *.html"
	}
}

stage ('Init'){
    
    node ('jenkins-slave') {
        

        // The map we'll store the parallel steps in before executing them.
        def stepsForParallel = [:]
        //Assign items to the map
        
        def scans = TARGETS.tokenize(",")
        
        for (int i = 0; i < scans.size(); i++) {
            // Get the actual string here.
            def step = scans.get(i)
            echo "step is: " + step
            def stepName = "${step}"
            // populate the map with closures
            stepsForParallel[stepName] = parallelstep(step)
        }

        //Pass the map to the parallel step
        parallel stepsForParallel

        
        
    }
    
}		

/*

def targets = TARGETS.tokenize(",")

stage ('nessus-scan') {
	node('jenkins-slave'){
		deleteDir()
		git url: 'git@bitbucket.org:ss8/scripts.git', branch: 'master'
		for (int i = 0; i < targets.size(); i++){
			def target = targets.get(i)
			sh 'chmod +x nessus/custom-scan.sh; nessus/custom-scan.sh ' + target
			//archive '*.txt, *.html'
		}
		stash name: "first-stash", includes: "*.txt, *.html"
	}
}

*/

stage ('Reporting') {
	
		node('jenkins-slave'){
			
			def TMPDIR = '/tmp/nessus-scan-logs'
			sh 'rm -rf ' + TMPDIR
			sh 'mkdir -p ' + TMPDIR
	
			ws ("${TMPDIR}") {

				echo "**************** Sending logs from here ********************"
				try{
					//unarchive mapping: ['*.html' : '.']
					//unarchive mapping: ['*.txt' : '.']
					for (int i = 0; i < targets.size(); i++){
						def target = targets.get(i)
						unstash target
					}
					// Format the body of email
					sh 'echo "Summary of Nessus Scan results:" >  Summary'
					sh 'echo "<br>" >> Summary'
					sh 'echo "------------------------------------------" >> Summary'
					sh 'echo "<br>" >> Summary'
					sh 'for file in `ls *.txt`; do cat "$file" >> Summary ; echo "<br>" >> Summary; done'
					emailext attachmentsPattern: '*.html', body: '${FILE,path="' + TMPDIR + '/Summary"}', subject: 'Nessus scan results for: ' + TARGETS, to: MAILING_LIST
					//emailext attachmentsPattern: '*.html', mimeType: 'text/html', body: '${FILE,path="' + TMPDIR + '/Summary.HTML"}', subject: 'Nessus scan results for: ' + TARGETS, to: MAILING_LIST
				}
				catch(err){
				    echo "***************** Could not send mail! **************"
					currentBuild.result = 'SUCCESS'    
				}
			} //end of ws block
		} //end of node block
} // end of stage

	