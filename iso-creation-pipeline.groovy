
stage ('rpm sync') {

    node {
        if (RPM_SYNC) {
            echo "Running rpm sync..."
            
            if (PLATFORM == 'RHEL-6' ) {
                node ('rhel6-iso-build-machine') {
                    echo "Running a repo sync with Redhat 6"
                    try { // Try to register with redhat first
                        sh 'sudo -u root -i /home/victorhugo/repos/subscribe.sh'
                    }
                    catch(err){ // If registration failed, unregister and re-register
                        sh 'sudo -u root -i /home/victorhugo/repos/unregister.sh'
                        sh 'sudo -u root -i /home/victorhugo/repos/subscribe.sh'
                    }
                     // Try to sync
                    try { 
                        
                        sh 'sudo -u root -i /home/victorhugo/repos/repoSync-NG.sh'
                    }
                    catch (err) {
                        currentBuildResult = 'FAILURE'
                        sh 'sudo -u root -i /home/victorhugo/repos/unregister.sh' // unregister before exiting
                        emailext body: 'Reposync for RHEL-6 failed ', attachLog: true, subject: 'Reposync for RHEL-6 failed', to: MAILING_LIST
						throw err
                    }
                    // if we got here the repo sync was successful
                    sh 'sudo -u root -i /home/victorhugo/repos/clean.sh' // Cleanup
                    sh 'sudo -u root -i /home/victorhugo/repos/repoFix.sh' //  Fix issues in repo
                    // Unsubscribe once done
                    sh 'sudo -u root -i /home/victorhugo/repos/unregister.sh'
                } // end of node
            } // end of rhel6 block
            
            if ( PLATFORM == 'RHEL-7' ) {
                node ('rhel7-repo') {
                    echo "Running a repo sync with Redhat 7"
                    // Try to register with redhat first
                    try { 
                        sh 'sudo -u root -i /home/iso-build-user/scripts/subscribe.sh'
                    }
                    catch(err){ // If registration failed, unregister and re-register
                        sh 'sudo -u root -i /home/iso-build-user/scripts/unregister.sh'
                        sh 'sudo -u root -i /home/iso-build-user/scripts/subscribe.sh'
                    }
                    // Try to sync
                    try { // sync repo
                        sh 'sudo -u root -i /home/iso-build-user/scripts/repo_sync.sh'
                    }
                    catch (err) {
                        currentBuildResult = 'FAILURE'
                        sh 'sudo -u root -i /home/iso-build-user/unregister.sh' // unregister before exiting
                        sh 'sudo -u root -i /home/iso-build-user/scripts/unregister.sh'
                        emailext body: 'Reposync for RHEL-7 failed ', attachLog: true, subject: 'Reposync for RHEL-7 failed', to: MAILING_LIST
						throw err
                    }
                    // if we got here the repo sync was successful
                    sh 'sudo -u root -i /home/iso-build-user/scripts/rhel7_repofix.sh' //  Fix issues in repo
                    // Unsubscribe once done
                    sh 'sudo -u root -i /home/iso-build-user/scripts/unregister.sh'
                } // end of node block
            } // end of rhel7 block
            
        } // end of if rpm-sync block
    } // end of node
} // end of rpm sync stage

// Email success
node {
	  emailext body: 'ISO Creation successful!', attachLog: true, subject: 'ISO Creation Successfull', to: MAILING_LIST
} 


