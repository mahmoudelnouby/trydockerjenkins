// Jenkinsfile: Unified pipeline for ms-template
// This pipeline merges Jenkinsfile1 and Jenkinsfile2, preferring Jenkinsfile2 where there are conflicts.
// It uses Docker multi-stage builds, extracts artifacts, publishes to Artifactory, and supports optional Docker Compose and build promotion.
// Each stage and field is described in comments for clarity.

pipeline {
  agent { label 'docker' } // Jenkins agent with Docker

  environment {
    // Artifactory and Docker registry configuration
    ARTIFACTORY_URL      = 'https://artifactory.example.com'          // <-- change as needed
    ARTIFACTORY_USER     = credentials('artifactory-username-id')     // Jenkins Username credential
    ARTIFACTORY_PASSWORD = credentials('artifactory-password-id')     // Jenkins Password credential
    // Or use token:
        // ARTIFACTORY_TOKEN = credentials('artifactory-token-id')
    DOCKER_REGISTRY      = 'artifactory.example.com/docker-local'     // Artifactory Docker registry

    // Application/image configuration
    APP_NAME    = 'ms-template'                                       // Application name
    IMAGE_NAME  = 'ms-template'                                       // Docker image name
    IMAGE_TAG   = "${env.BUILD_NUMBER}"                               // Tag per build
    BUILD_NAME  = 'ms-template-ci'
    BUILD_NUMBER= "${env.BUILD_NUMBER}"
  }

  stages {
    stage('Checkout') {
      steps {
        // Clone the repository
        checkout scm
      }
    }

    stage('Prepare JFrog CLI') {
      steps {
        // Download and configure JFrog CLI for Artifactory
        sh '''
          if ! command -v jfrog >/dev/null 2>&1; then
            curl -fL https://getcli.jfrog.io | sh
            sudo mv jfrog /usr/local/bin/jfrog || mv jfrog ~/.local/bin/jfrog || true
          fi
          jfrog config add artifactory-server \
            --artifactory-url="${ARTIFACTORY_URL}" \
            --user="${ARTIFACTORY_USER}" \
            --password="${ARTIFACTORY_PASSWORD}" \
            --interactive=false || true
          # If using token:
                    # jfrog config add artifactory-server \
                    #   --artifactory-url="${ARTIFACTORY_URL}" \
                    #   --access-token="${ARTIFACTORY_TOKEN}" \
                    #   --interactive=false || true
          jfrog config use artifactory-server
        '''
      }
    }

    stage('Build (Docker multi-stage: Maven verify)') {
      steps {
        // Build Docker image; Maven build and tests run inside container
        sh '''
          docker build --pull -t ${IMAGE_NAME}:${IMAGE_TAG} .
        '''
      }
    }

    stage('Extract Test Reports & JAR') {
      steps {
        // Extract JAR and test reports from the build container
        sh '''
          # Rebuild targeting only the build stage so we can extract from it if needed
          docker build --target build -t ${IMAGE_NAME}-build:${IMAGE_TAG} .

          # Create a temp container and copy artifacts out
          CID=$(docker create ${IMAGE_NAME}-build:${IMAGE_TAG})
          mkdir -p target surefire-reports

          # Copy JARs (adjust filename if you use shaded or spring-boot jar)
          docker cp $CID:/usr/app/target/. ./target/

          # Copy Maven Surefire test reports for Jenkins JUnit
          docker cp $CID:/usr/app/target/surefire-reports/. ./surefire-reports/ || true
          docker rm $CID

          # Normalize single main jar to app.jar (if multiple jars exist, pick your main one)
          MAIN_JAR=$(ls -1 target/*.jar | head -n1)
          cp "$MAIN_JAR" ms-template.jar
        '''
      }
      post {
        always {
          // Publish JUnit test results and archive artifacts
          junit allowEmptyResults: true, testResults: 'surefire-reports/*.xml'
          archiveArtifacts artifacts: 'target/*.jar, surefire-reports/*.xml', fingerprint: true
        }
      }
    }

    stage('Checkstyle') {
      steps {
        // Run Checkstyle and publish HTML report
        sh '''
          mvn checkstyle:checkstyle
        '''
        publishHTML([
          reportName: 'Checkstyle',
          reportDir: 'target/site',
          reportFiles: 'checkstyle.html',
          keepAll: true,
          alwaysLinkToLastBuild: true,
          allowMissing: true
        ])
        sh 'mvn checkstyle:check'
      }
    }

    stage('Upload JAR to Artifactory') {
      steps {
        // Upload JAR to Artifactory with build info
        sh '''
          jfrog rt u "ms-template.jar" "libs-snapshot-local/${IMAGE_NAME}/${IMAGE_TAG}/" \
            --build-name="${BUILD_NAME}" --build-number="${BUILD_NUMBER}" --flat=true

          # Collect & publish build-info (links commits → artifacts)
          jfrog rt bce "${BUILD_NAME}" "${BUILD_NUMBER}"
          jfrog rt bp  "${BUILD_NAME}" "${BUILD_NUMBER}"
        '''
      }
    }

    stage('Login to Artifactory Docker Registry') {
      steps {
        // Authenticate Docker to Artifactory registry
        sh '''
          docker login ${DOCKER_REGISTRY} -u "${ARTIFACTORY_USER}" -p "${ARTIFACTORY_PASSWORD}"
          # If using token:
                    # echo "${ARTIFACTORY_TOKEN}" | docker login ${DOCKER_REGISTRY} --username "token" --password-stdin
        '''
      }
    }

    stage('Tag & Push Docker Image to Artifactory') {
      steps {
        // Tag and push Docker image to Artifactory, attach build info
        sh '''
          docker tag ${IMAGE_NAME}:${IMAGE_TAG} ${DOCKER_REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG}
          docker push ${DOCKER_REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG}

          # Optionally push 'latest'
          docker tag ${IMAGE_NAME}:${IMAGE_TAG} ${DOCKER_REGISTRY}/${IMAGE_NAME}:latest
          docker push ${DOCKER_REGISTRY}/${IMAGE_NAME}:latest

          # Attach docker layers to build-info in Artifactory
          jfrog rt docker-push ${DOCKER_REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG} docker-local \
            --build-name="${BUILD_NAME}" --build-number="${BUILD_NUMBER}"
          jfrog rt bp "${BUILD_NAME}" "${BUILD_NUMBER}"
        '''
      }
    }

    stage('(Optional) Deploy with Docker Compose') {
      when { expression { fileExists('docker-compose.yml') } }
      steps {
        // Deploy locally with Docker Compose (optional)
        sh 'docker-compose down || true'
        sh 'docker-compose up -d'
      }
    }

    stage('(Optional) Promote Build to Release Repo') {
      when { expression { return env.BRANCH_NAME == 'main' } }
      steps {
        // Promote build to release repo if on main branch
        sh '''
          jfrog rt bpr "${BUILD_NAME}" "${BUILD_NUMBER}" libs-release-local \
            --comment="Promoted from snapshot to release" --status="Released"
        '''
      }
    }
  }

  post {
    always {
      // Clean workspace after build
      cleanWs()
    }
    success {
      echo "Build ${BUILD_NUMBER} JAR + Docker image published to Artifactory."
    }
    failure {
      echo "Build failed — check logs."
    }
  }
}

// Field and Stage Descriptions:
// - agent: Specifies the Jenkins agent (with Docker) to run the pipeline.
// - environment: Sets global environment variables for Artifactory, Docker, and app config.
// - stages: Pipeline steps, each with a specific purpose:
//   - Checkout: Clones the source code from the repository.
//   - Prepare JFrog CLI: Installs and configures JFrog CLI for Artifactory operations.
//   - Build (Docker multi-stage): Builds the Docker image, running Maven build/tests inside the container.
//   - Extract Test Reports & JAR: Extracts built JAR and test reports from the Docker build container.
//   - Checkstyle: Runs Checkstyle and publishes the HTML report; fails build on violations.
//   - Upload JAR to Artifactory: Uploads the JAR to Artifactory with build info.
//   - Login to Artifactory Docker Registry: Authenticates Docker to Artifactory registry.
//   - Tag & Push Docker Image: Tags and pushes Docker image to Artifactory, attaches build info.
//   - (Optional) Deploy with Docker Compose: Deploys locally with Docker Compose if docker-compose.yml exists.
//   - (Optional) Promote Build: Promotes build to release repo if on main branch.
// - post: Always cleans workspace; logs build result.

