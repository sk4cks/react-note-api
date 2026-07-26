// react-note-api repo 루트에 Jenkinsfile 로 복사
pipeline {
  agent any
  options {
    buildDiscarder(logRotator(numToKeepStr: '1', artifactNumToKeepStr: '1'))
  }
  environment {
    AWS_REGION = 'ap-northeast-2'
    ECR_URI = '019511184889.dkr.ecr.ap-northeast-2.amazonaws.com/react-note-api'
    GITOPS_REPO = 'https://github.com/sk4cks/react-note-deploy.git'
    GITOPS_MANIFEST = 'react-note-api'
  }
  stages {
    stage('Checkout') {
      steps { checkout scm }
    }
    stage('Build JAR') {
      steps {
        sh 'chmod +x gradlew && ./gradlew bootJar -x test'
      }
    }
    stage('Docker Push') {
      steps {
        sh '''
          set -eux
          bash /scripts/install-ci-tools.sh
          aws ecr get-login-password --region $AWS_REGION | docker login --username AWS --password-stdin ${ECR_URI%/*}
          docker build --platform linux/amd64 -t $ECR_URI:${BUILD_NUMBER} -t $ECR_URI:latest .
          docker push $ECR_URI:${BUILD_NUMBER}
          docker push $ECR_URI:latest
        '''
      }
    }
    stage('Update GitOps') {
      steps {
        dir('gitops') {
          git url: "${GITOPS_REPO}", branch: 'main', credentialsId: 'github-gitops'
          withCredentials([usernamePassword(credentialsId: 'github-gitops', usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
            sh """
              sed -i 's|image: ${ECR_URI}:.*|image: ${ECR_URI}:${BUILD_NUMBER}|' k8s/${GITOPS_MANIFEST}.yaml
              git config user.email 'jenkins@local'
              git config user.name 'jenkins'
              git add k8s/${GITOPS_MANIFEST}.yaml
              git diff --cached --quiet || git commit -m 'ci(api): image ${BUILD_NUMBER}'
              git remote set-url origin https://\${GH_USER}:\${GH_TOKEN}@github.com/sk4cks/react-note-deploy.git
              git push origin main
            """
          }
        }
      }
    }
  }
}
