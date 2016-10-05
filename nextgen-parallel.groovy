// IPADDRESSES should be supplied as "1.1.1.1 2.2.2.2", "3.3.3.3 4.4.4.4", "5.5.5.5 6.6.6.6", "7.7.7.7 8.8.8.8"
// TEST_SUITES should be supplied as "lev1, lev2, lev3, kddi, reg, tele, rtf, etc.
def ippair = IPADDRESSES.tokenize(",")

// The map we'll store the parallel steps in before executing them.
def stepsForParallel = [:]

for (int i = 0; i < ippair.size(); i++) {
    // Get the actual string here.
    def s = ippair.get(i)
    def stepName = "echoing ${s}"
    
    stepsForParallel[stepName] = parallelstep(s)
} // for loop

parallel stepsForParallel

def parallelstep(inputString) {
    return {
        node {
            stage('Pair:' + inputString) {
                echo "Working on the pair:"  + inputString
                ip = inputString.tokenize()
                echo "The first IP is: " + ip[0]
                echo "The second IP is: " + ip[1]
            } //stage block
        } // node block
    } //return block
} // def block

