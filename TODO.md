# klab.product TODO

### Tasks
- [ ] Add an update-product goal for the root pom.xml to create product files from the products corresponding to the existing releases. If a distributionUrl parameter is given, retrieve the remote distribution file and product files from the URL and produce an updated version.  Parameters can control the number of releases kept in it.
- [ ] Add an update-distribution goal for the root pom.xml to create the distribution file from all products found in the previous goal. If a distributionUrl parameter is given, retrieve the remote distribution file and produce an updated version.
- [ ] Add a run-tests goal for the root pom.xml that will use the built runtime to retrieve a set of git project URLs passed as parameters, run all test cases in them, and store the output in a designated directory. 
