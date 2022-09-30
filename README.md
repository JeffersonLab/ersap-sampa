# ersap-sampa
### Java Binding
#### Build notes

The project requires JDK 11 or higher.

#### Ubuntu

    $ sudo add-apt-repository ppa:webupd15team/java
    $ sudo apt-get update
    $ sudo apt-get install oracle-java15-installer

Check the version:

    $ java -version
    $ java 15.0.1 2020-10-20
    $ Java(TM) SE Runtime Environment (build 15.0.1+9-18)
    $ Java HotSpot(TM) 64-Bit Server VM (build 15.0.1+9-18, mixed mode, sharing)
    You may need the following package to set Java 15 as default
    $ sudo apt-get install oracle-java8-set-default

You can also set the default Java version with `update-alternatives`:

    $ sudo update-alternatives --config java

#### macOS

Install Oracle Java using [Homebrew](https://brew.sh/):

    $ brew cask install caskroom/versions/java8

### Installation
git clone https://github.com/JeffersonLab/ersap-sampa.git

To build ersap-sampa use the provided [Gradle](https://gradle.org/) wrapper.
It will download the required Gradle version and all the ERSAP dependencies.

    $ ./gradlew clean
    $ ./gradlew 

To deploy the binary distribution to `$ERSAP_HOME`:

    $ ./gradlew deploy
