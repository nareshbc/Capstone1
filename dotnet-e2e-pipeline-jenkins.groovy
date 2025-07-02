pipeline {
  agent any

  //––––– Pipeline parameters –––––
  parameters {
    string(name: 'SONAR_PROJECT_KEY',      defaultValue: 'Default_Sonar_Project_Key', description: 'Sonar Project Key')
    string(name: 'RG_NAME',                defaultValue: 'eShopOnWeb',                description: 'Azure Resource-group name')
    string(name: 'WEBSITE_NAME',           defaultValue: 'eshop',                     description: 'WebApp name prefix')
    string(name: 'ESHOP_SERVER_NAME',      defaultValue: 'eshop',                     description: 'Server name parameter for ARM')
    string(name: 'API_KEY',                defaultValue: 'apikey',                    description: 'OpenAI API key (if used)')
    string(name: 'BUILD_CONFIGURATION',    defaultValue: 'Release',                   description: 'Dotnet build configuration')
  }

  //––––– Global environment & tools –––––
  environment {
    AZ_CREDENTIALS = credentials('AzureServicePrincipal')  
    // if you store a Sonar token as a Secret Text credential:
    SONAR_TOKEN    = credentials('SonarQubeToken')         
  }
  tools {
    dotnet 'dotnet7'
  }

  stages {
    stage('Checkout') {
      steps {
        checkout scm
      }
    }

    stage('Restore') {
      steps {
        sh 'dotnet restore **/*.csproj'
      }
    }

    stage('Build') {
      steps {
        sh "dotnet build --configuration ${params.BUILD_CONFIGURATION}"
      }
    }

    stage('Test & Coverage') {
      steps {
        // Run tests and collect coverage in the standard “XPlat Code Coverage” format
        sh "dotnet test --configuration ${params.BUILD_CONFIGURATION} --collect:\"XPlat Code Coverage\""
      }
      post {
        always {
          // publish coverage report via Cobertura plugin
          cobertura autoUpdateHealth: false,
                   coberturaReportFile: '**/coverage.cobertura.xml',
                   failUnhealthy: true,
                   failUnstable: true

          // enforce a 35% line-coverage threshold
          script {
            def cov = currentBuild.rawBuild
                          .getAction(hudson.plugins.cobertura.CoberturaBuildAction)
                          .result.coverage['LINE']
                          .percentage
            if (cov < 35) {
              error "Line coverage ${cov}% is below the threshold of 35%"
            }
          }
        }
      }
    }

    stage('OpenAI Code Review') {
      steps {
        // replicate your Azure OpenAI task here, for example:
        sh """
          curl -X POST https://api.openai.com/v1/chat/completions \\
            -H 'Authorization: Bearer ${params.API_KEY}' \\
            -H 'Content-Type: application/json' \\
            -d '{
              "model":"gpt-4o-mini",
              "messages":[{"role":"user","content":"Review code for security, naming, indentation, error handling"}]
            }' > openai-review.json
        """
        // you can archive or parse openai-review.json as needed
        archiveArtifacts artifacts: 'openai-review.json', fingerprint: true
      }
    }

    stage('SonarQube Analysis') {
      when { expression { env.CHANGE_ID == null } }  // skip on pull‐requests
      steps {
        withSonarQubeEnv('SonarQube') {
          sh """
            dotnet sonarscanner begin \\
              /k:${params.SONAR_PROJECT_KEY} \\
              /d:sonar.login=${env.SONAR_TOKEN}
            dotnet build --configuration ${params.BUILD_CONFIGURATION}
            dotnet sonarscanner end /d:sonar.login=${env.SONAR_TOKEN}
          """
        }
      }
    }

    stage('CodeQL Analysis') {
      when { expression { env.CHANGE_ID == null } }
      steps {
        sh """
          # initialize a CodeQL database and analyze
          codeql database create codeql-db --language=csharp --source-root=.
          codeql database analyze codeql-db \\
            --format=sarif-latest --output=codeql-results.sarif
        """
      }
      post {
        always {
          // publish SARIF results
          publishIssues failedThreshold: 1,
                        healthy: 1,
                        minimumSeverity: 'LOW',
                        tool: codeQL(pattern: 'codeql-results.sarif')
        }
      }
    }

    stage('Dependency Scanning') {
      when { expression { env.CHANGE_ID == null } }
      steps {
        sh """
          scanoss --sbom --output sbom.json
          scanoss --policies copyleft,undeclared --halt-on-failure false
        """
        archiveArtifacts artifacts: 'sbom.json', fingerprint: true
      }
    }

    stage('Publish Artifacts') {
      steps {
        sh "dotnet publish --configuration ${params.BUILD_CONFIGURATION} --output publish"
        archiveArtifacts artifacts: 'publish/**', fingerprint: true
      }
    }

    stage('Copy ARM Templates') {
      steps {
        // stash so downstream stages can pick them up
        stash name: 'armTemplates', includes: 'env/eshopenv/*'
      }
    }

    stage('Test Deployment') {
      when { expression { env.CHANGE_ID == null } }
      steps {
        // unstash we just published
        unstash 'armTemplates'
        azureCLI azureCredentialsId: "${env.AZ_CREDENTIALS}",
                 scriptType: 'bash',
                 scriptLocation: 'inlineScript',
                 inlineScript: """
                   # create or update RG deployment
                   az group deployment create \\
                     --resource-group ${params.RG_NAME} \\
                     --template-file env/eshopenv/eShopOnWebResource.json \\
                     --parameters @env/eshopenv/eShopOnWeb.param.json \\
                     WebsiteName=${params.WEBSITE_NAME} \\
                     eshop_ServerName=${params.ESHOP_SERVER_NAME}

                   # deploy the WebApp to test slot
                   az webapp deploy \\
                     --resource-group ${params.RG_NAME} \\
                     --name ${params.WEBSITE_NAME}-test \\
                     --src-path publish/Web.zip
                 """
      }
    }

    stage('Run Security Tests') {
      when { expression { env.CHANGE_ID == null } }
      steps {
        // Start OWASP ZAP in Docker
        sh 'docker run -d -u zap -p 8913:8080 --name owasp-zap zaproxy/zap-stable zap.sh -daemon -port 8080 -host 0.0.0.0 -config api.key=zap_key'
        sleep 10

        // Execute scans
        sh """
          docker exec owasp-zap zap.sh -cmd -quickurl https://${params.WEBSITE_NAME}-test.azurewebsites.net/api/calculator/ \\
            -quickprogress -apikey zap_key > zap_report.xml
        """
        // Stop ZAP
        sh 'docker stop owasp-zap'
      }
      post {
        always {
          // publish the ZAP report as a JUnit‐style XML
          junit allowEmptyResults: true, testResults: '**/zap_report.xml'
        }
      }
    }

    stage('Production Deployment') {
      when { expression { env.CHANGE_ID == null } }
      steps {
        azureCLI azureCredentialsId: "${env.AZ_CREDENTIALS}",
                 scriptType: 'bash',
                 scriptLocation: 'inlineScript',
                 inlineScript: """
                   az webapp deploy \\
                     --resource-group ${params.RG_NAME} \\
                     --name ${params.WEBSITE_NAME} \\
                     --src-path publish/Web.zip
                 """
      }
    }
  }

  post {
    always {
      // Send notifications, cleanup, etc.
      echo "Pipeline finished with status: ${currentBuild.currentResult}"
    }
  }
}