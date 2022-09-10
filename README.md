# bwLehrpool-Server - Management Server for Virtual Machines and Events

The bwLehrpool-Server is a [bwLehrpool](https://www.bwlehrpool.de) server application to manage virtual machines and events for study and coursework.
Already created virtual machines can be uploaded to the bwLehrpool-Server to share those virtual machines with students and workgroup members as well as link them to events.
A virtual machine download from the bwLehrpool-Server allows customization of virtual machines locally for your purposes.


## Build
A build of the bwLehrpool-Server requires the installation of the build automation tool [Maven](https://maven.apache.org) and the Java development kit [OpenJDK 8](https://openjdk.java.net/projects/jdk8).

### Dependencies
If a Maven offline build takes place (`mvn -o`), the following dependencies are required and must be built manually first:

  - [master-sync-shared](https://git.openslx.org/bwlp/master-sync-shared.git)

### Application
The bwLehrpool-Server application can be built automatically by calling Maven with the following command line call:

```shell
mvn clean package
```

A build of the bwLehrpool-Server application creates a Java archive file (\*.jar) that can be found at `target/dozmod-server-*-jar-with-dependencies.jar`.


## Configuration

### Server
The bwLehrpool-Server application requires a Java property configuration file for its operation.
A template for this configuration file can be found at `setup/config.properties.tmpl`.
The template can be finalized with the [dockerize](https://github.com/jwilder/dockerize) utility using the following command line call where `DOZMOD_*` are environment variables to finalize the content of the configuration file:

```shell
export DOZMOD_DATABASE_HOST="192.168.200.20"
export DOZMOD_DATABASE_NAME="sat"
export DOZMOD_DATABASE_USER="root"
export DOZMOD_DATABASE_PASSWORD="dozmod"
export DOZMOD_DATABASE_LOCATION_TABLE=""
export DOZMOD_MASTER_SERVER_HOST="bwlp-masterserver.ruf.uni-freiburg.de"
export DOZMOD_MASTER_SERVER_PORT="9091"
export DOZMOD_MASTER_SERVER_USE_SSL="true"
export DOZMOD_SERVER_WEB_BIND_LOCALHOST="false"
export DOZMOD_VSTORE_PATH="/mnt/bwLehrpool"

# finalize the template content with values from environment variables
dockerize -template setup/config.properties.tmpl:config.properties
```

### Logging
The logging of the bwLehrpool-Server application to the console is implemented with [log4j](https://logging.apache.org/log4j).

#### Default Logging Configurations
The default logging configurations are specified in the property file under `src/main/properties/log4j.properties`.
This configuration file is packaged automatically into the bwLehrpool-Server application during a build.

#### Overwrite Logging Defaults
If logging configurations other than the defaults are required, the configuration file packaged into the bwLehrpool-Server application can be overwritten by specifying a custom configuration file.
The custom configuration file is specified by execute the bwLehrpool-Server application with the following command line call where `<CONFIG>` is an absolute path to the custom configuration file and `<APP>` is a path to the built bwLehrpool-Server application (Java archive file):

```shell
java -Dlog4j.configurationFile=<CONFIG> -jar <APP>
```


## Execution

### Standalone
An execution of the bwLehrpool-Server application requires an already finalized bwLehrpool-Server configuration file located in the current working directory.
This configuration file can be created from a configuration template as explained above.
Then, the bwLehrpool-Server application can be executed with the following command line call where `<APP>` is a path to the built bwLehrpool-Server application (Java archive file):

```shell
java -jar <APP>
```

### Docker Infrastructure
This project also provides a Docker infrastructure (images, containers, networks, and volumes) in which the bwLehrpool-Server application can be embedded and executed for test purposes.
The infrastructure consists of the following services and is created and started automatically using [docker-compose](https://docs.docker.com/compose):

| Service           | Container         | IPv4 Address   | IPv6 Address          |
|:------------------|:------------------|:---------------|:----------------------|
| bwLehrpool-Server | dozmod-server     | 192.168.200.20 | fd03:4b1d:5707:c8::14 |
| MariaDB           | dozmod-database   | 192.168.200.21 | fd03:4b1d:5707:c8::15 |
| phpMyAdmin        | dozmod-phpmyadmin | 192.168.200.22 | fd03:4b1d:5707:c8::16 |

A Docker image of the bwLehrpool-Server application and its required infrastructure (images, containers, networks, and volumes) is created and started automatically with the following command line call:

```shell
mvn -P dozmod-server:start
```

The Docker infrastructure started with the above call uses the production default bwLehrpool-Master-Server of the University of Freiburg.
If the custom bwLehrpool-Master-Server of the Docker infrastructure from the [masterserver](https://git.openslx.org/bwlp/masterserver.git) repository should be used instead, the default configuration of the above command line call can be overwritten by using the following command line call instead:

```shell
mvn -P dozmod-server:start -P dozmod-server:config:custom-master-server
```

Note that the complete Docker infrastructure (images, containers, networks, and volumes) is created and started automatically after a start call.
To stop the bwLehrpool-Server container and its entire infrastructure, use the following command line call:

```shell
mvn -P dozmod-server:stop
```
