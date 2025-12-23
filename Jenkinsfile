// Jenkinsfile: Podman/Docker local registry + tests + checkstyle + JFrog

pipeline {
  agent any

  options {
    timestamps()
    ansiColor('xterm')
    skipDefaultCheckout(true)
  }

  environment {
    // ===== App / Image =====
    IMAGE_NAME   = 'ms-template'
    IMAGE_TAG    = "${env.BUILD_NUMBER}"
    APP_JAR      = 'ms-template.jar'   // normalized name we’ll publish

    // ===== Local Registry (CHANGE THIS) =====
    // Use an address reachable from inside the Jenkins container:
    //  - 'registry:5000' (service name on same network)
    //  - 'host.containers.internal:5000' (Podman host)
    //  - '<host-ip>:5000' (explicit host IP)
    REGISTRY     = 'host.containers.internal:5000'

    // Set to 'true' if the local registry is insecure (no TLS)
    INSECURE_REGISTRY = 'true'

    // ===== Artifactory / JFrog (CHANGE THESE) =====
    ARTIFACTORY_URL   = 'https://artifactory.example.com'          // <-- change
    // Provide either USER/PASSWORD credentials IDs or a TOKEN ID:
    ARTIFACTORY_USER_ID     = 'artifactory-username-id'            // Jenkins "Username" cred ID
    ARTIFACTORY_PASSWORD_ID = 'artifactory-password-id'            // Jenkins "Password" cred ID
    // ARTIFACTORY_TOKEN_ID = 'artifactory-token-id'               // Jenkins "Secret text" cred ID

    // Build info for JFrog
    BUILD_NAME   = 'ms-template-ci'
    BUILD_NUMBER = "${env.BUILD_NUMBER}"

    // Will be set in Detect stage: 'podman' or 'docker'
    CONTAINER_CLI = ''
  }

  stages {

    // ---------------------------------------------------------------------
    // Detect Podman/Docker
    // ---------------------------------------------------------------------
    stage('Detect Container CLI') {
      steps {
        script {
          def cli = sh(script: '''
            if command -v podman >/dev/null 2>&1; then echo podman;
            elif command -v docker >/dev/null 2>&1; then echo docker;
            else echo none; fi
          ''', returnStdout: true).trim()
          if (cli == 'none') {
            error "Neither podman nor docker found in PATH. Ensure the CLI is installed, or map the host socket."
          }
          env.CONTAINER_CLI = cli
          echo "Using container CLI: ${env.CONTAINER_CLI}"
        }
      }
    }

    // ---------------------------------------------------------------------
    // Checkout
    // ---------------------------------------------------------------------
    stage('Checkout') {
      steps {
        // Force a stable workspace path so later container bind-mounts
        // always point at the same checked-out directory.
        ws("${env.WORKSPACE}") {
          deleteDir()
          checkout(scm)
          sh '''
            set -eux
            test -d .git
            git rev-parse --is-inside-work-tree
          '''
        }
      }
    }

    // ---------------------------------------------------------------------
    // Unit Tests (Maven inside container)
    // ---------------------------------------------------------------------
    stage('Unit Tests') {
      steps {
        // Run tests in a Maven container so Jenkins host doesn’t need Maven.
        // Use Jenkins' WORKSPACE instead of $PWD to avoid path drift.
        ws("${env.WORKSPACE}") {
          sh '''
            set -eux
            ${CONTAINER_CLI} run --rm \
              -v "${WORKSPACE}:/usr/app" -w /usr/app \
              maven:3.8.8-eclipse-temurin-17 \
              mvn -B -ntp verify
          '''
        }
      }
      post {
        always {
          junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml'
          archiveArtifacts artifacts: 'target/**', fingerprint: true
        }
      }
    }

    // ---------------------------------------------------------------------
    // Checkstyle (HTML report + fail on violations)
    // ---------------------------------------------------------------------
    stage('Checkstyle') {
      steps {
        ws("${env.WORKSPACE}") {
          sh '''
            set -eux
            ${CONTAINER_CLI} run --rm \
              -v "${WORKSPACE}:/usr/app" -w /usr/app \
              maven:3.8.8-eclipse-temurin-17 \
              mvn -B -ntp checkstyle:checkstyle
          '''
        }

        publishHTML([
          reportName: 'Checkstyle',
          reportDir: 'target/site',
          reportFiles: 'checkstyle.html',
          keepAll: true,
          alwaysLinkToLastBuild: true,
          allowMissing: true
        ])

        ws("${env.WORKSPACE}") {
          sh '''
            set -eux
            ${CONTAINER_CLI} run --rm \
              -v "${WORKSPACE}:/usr/app" -w /usr/app \
              maven:3.8.8-eclipse-temurin-17 \
              mvn -B -ntp checkstyle:check
          '''
        }
      }
    }

    // ---------------------------------------------------------------------
    // Build Image (multi-stage) — matches your Dockerfile
    // ---------------------------------------------------------------------
    stage('Build Image') {
      steps {
        sh '''
          set -eux
          ${CONTAINER_CLI} build --pull -t ${IMAGE_NAME}:${IMAGE_TAG} .
        '''
      }
    }

    // ---------------------------------------------------------------------
    // Extract JAR from build stage image
    // ---------------------------------------------------------------------
    stage('Extract JAR from Image') {
      steps {
        ws("${env.WORKSPACE}") {
          sh '''
            set -eux
            # Build the specific build stage image (FROM maven ... AS build)
            ${CONTAINER_CLI} build --target build -t ${IMAGE_NAME}-build:${IMAGE_TAG} .

            CID=$(${CONTAINER_CLI} create ${IMAGE_NAME}-build:${IMAGE_TAG})
            mkdir -p target

            # Copy JAR from /usr/app/target to workspace, normalize name
            ${CONTAINER_CLI} cp $CID:/usr/app/target/. ./target/ || true
            ${CONTAINER_CLI} rm $CID

            MAIN_JAR=$(ls -1 target/*.jar | head -n1 || true)
            if [ -n "$MAIN_JAR" ]; then cp "$MAIN_JAR" "${APP_JAR}"; fi
          '''
        }
      }
      post {
        always {
          // Archive both the built JAR(s) and the normalized APP_JAR.
          archiveArtifacts artifacts: 'target/*.jar,ms-template.jar', fingerprint: true
        }
      }
    }

    // ---------------------------------------------------------------------
    // Prepare JFrog CLI & configure Artifactory
    // ---------------------------------------------------------------------
    stage('Prepare JFrog CLI') {
      steps {
        sh '''
          set -eux
          export PATH="$HOME/.local/bin:$PATH"
          if ! command -v jfrog >/dev/null 2>&1; then
            if command -v curl >/dev/null 2>&1; then
              curl -fL https://getcli.jfrog.io | sh
            elif command -v wget >/dev/null 2>&1; then
              wget -qO- https://getcli.jfrog.io | sh
            else
              echo "ERROR: curl/wget not found"; exit 1
            fi
            mkdir -p "$HOME/.local/bin"
            mv jfrog "$HOME/.local/bin/jfrog"
          fi
        '''
        script {
          if (env.ARTIFACTORY_TOKEN_ID) {
            withCredentials([string(credentialsId: env.ARTIFACTORY_TOKEN_ID, variable: 'ARTIFACTORY_TOKEN')]) {
              sh '''
                jfrog config add artifactory-server \
                  --artifactory-url="${ARTIFACTORY_URL}" \
                  --access-token="${ARTIFACTORY_TOKEN}" \
                  --interactive=false || true
                jfrog config use artifactory-server
              '''
            }
          } else {
            withCredentials([usernamePassword(credentialsId: env.ARTIFACTORY_USER_ID,
                                             usernameVariable: 'ART_USER',
                                             passwordVariable: 'ART_PASS')]) {
              sh '''
                jfrog config add artifactory-server \
                  --artifactory-url="${ARTIFACTORY_URL}" \
                  --user="${ART_USER}" \
                  --password="${ART_PASS}" \
                  --interactive=false || true
                jfrog config use artifactory-server
              '''
            }
          }
        }
      }
    }

    // ---------------------------------------------------------------------
    // Upload JAR to Artifactory + build info
    // ---------------------------------------------------------------------
    stage('Upload JAR to Artifactory') {
      steps {
        sh '''
          set -eux
          jfrog rt u "${APP_JAR}" "libs-snapshot-local/${IMAGE_NAME}/${IMAGE_TAG}/" \
            --build-name="${BUILD_NAME}" --build-number="${BUILD_NUMBER}" --flat=true

          # Collect & publish build-info
          jfrog rt bce "${BUILD_NAME}" "${BUILD_NUMBER}"
          jfrog rt bp  "${BUILD_NAME}" "${BUILD_NUMBER}"
        '''
      }
    }

    // ---------------------------------------------------------------------
    // Login to Local Registry (optional)
    // ---------------------------------------------------------------------
    stage('Local Registry Login (optional)') {
      when { expression { return env.INSECURE_REGISTRY == 'false' } } // skip for insecure registry
      steps {
        // If your registry requires auth, create Jenkins credentials and use withCredentials:
        // withCredentials([usernamePassword(credentialsId: 'local-registry-cred',
        //                                   usernameVariable: 'REG_USER',
        //                                   passwordVariable: 'REG_PASS')]) {
        //   sh """
        //     set -eux
        //     ${CONTAINER_CLI} login ${REGISTRY} -u "${REG_USER}" -p "${REG_PASS}"
        //   """
        // }
        echo 'Skipping login (no credentials configured or using insecure registry).'
      }
    }

    // ---------------------------------------------------------------------
    // Tag & Push image to Local Registry
    // ---------------------------------------------------------------------
    stage('Tag & Push to Local Registry') {
      steps {
        sh '''
          set -eux
          IMAGE_REF="${REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG}"
          LATEST_REF="${REGISTRY}/${IMAGE_NAME}:latest"

          TLS_FLAG=""
          if [ "${CONTAINER_CLI}" = "podman" ] && [ "${INSECURE_REGISTRY}" = "true" ]; then
            TLS_FLAG="--tls-verify=false"
          fi

          ${CONTAINER_CLI} tag ${IMAGE_NAME}:${IMAGE_TAG} "${IMAGE_REF}"
          ${CONTAINER_CLI} push ${TLS_FLAG} "${IMAGE_REF}"

          ${CONTAINER_CLI} tag ${IMAGE_NAME}:${IMAGE_TAG} "${LATEST_REF}"
          ${CONTAINER_CLI} push ${TLS_FLAG} "${LATEST_REF}"
        '''
      }
    }

    // ---------------------------------------------------------------------
    // (Optional) Promote build in Artifactory when merging to main
    // ---------------------------------------------------------------------
    stage('(Optional) Promote Build to Release Repo') {
      when { expression { return env.BRANCH_NAME == 'main' } }
      steps {
        sh '''
          jfrog rt bpr "${BUILD_NAME}" "${BUILD_NUMBER}" libs-release-local \
            --comment="Promoted from snapshot to release" --status="Released"
        '''
      }
    }

    // ---------------------------------------------------------------------
    // (Optional) Local deploy with Compose
    // ---------------------------------------------------------------------
    stage('(Optional) Deploy with Compose') {
      when { expression { fileExists('docker-compose.yml') || fileExists('compose.yaml') } }
      steps {
        sh '''
          set -eux
          if command -v podman >/dev/null 2>&1; then
            if command -v podman-compose >/dev/null 2>&1; then
              podman-compose down || true
              podman-compose up -d
            else
              echo "podman-compose not found; skipping optional deploy."
            fi
          else
            if docker compose version >/dev/null 2>&1; then
              docker compose down || true
              docker compose up -d
            else
              docker-compose down || true
              docker-compose up -d
            fi
          fi
        '''
      }
    }
  }

  post {
    always { cleanWs() }
    success { echo "Build ${IMAGE_TAG}: tests/Checkstyle passed, JAR uploaded to Artifactory, image pushed to ${REGISTRY}." }
    failure { echo "Build failed — check logs." }
  }
}
