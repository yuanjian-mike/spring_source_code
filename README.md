#1. 如何编译
  ##### 为了节约脚本下载gradle的时间，我们去
  ##### https://services.gradle.org/distributions/ 
  ##### 下载一个gradle，版本为5.6.4，然后修改gradle-wrapper.properties
    distributionBase=GRADLE_USER_HOME
    distributionPath=wrapper/dists
    #distributionUrl=https\://services.gradle.org/distributions/gradle-5.6.4-bin.zip
    distributionUrl=file\:///d\:/gradle/gradle-5.6.4-bin.zip
    zipStoreBase=GRADLE_USER_HOME
    zipStorePath=wrapper/dists
  
  ##### 然后为了节约下载依赖时间，我们选择使用阿里作为镜像中心，修改 build.gradle 在 repositories 下 添加
    maven { url "https://repo.spring.io/libs-spring-framework-build" }
    maven { url "https://repo.spring.io/snapshot" } // Reactor
    maven {url 'https://maven.aliyun.com/nexus/content/groups/public/'} //阿里云
    maven {url 'https://maven.aliyun.com/nexus/content/repositories/jcenter'}
  
  ##### 执行 gradlew.bat 执行成功后会显示绿色的 BUILD SUCCESSFUL
  ##### 然后打开IDEA，进入Settings-->Build, Execution, Deployment-->Build tool-->Gradle
  ##### 设置好Gradle，这里有个坑，如果Build跟run都选择Gradle的话，那么你debug调试的时候达不到你预期的效果，
  ##### 会等你断点执行完后才去执行代码，然后执行一大堆Task, 达不到调试作用
  ##### 所以这里我们吧这两个选项改为IDEA的，应用后导入项目，然后就“开启”你的调试模式吧
  
    
  
    
    