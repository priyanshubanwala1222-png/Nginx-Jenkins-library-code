#!/usr/bin/env groovy
//
// vars/runFullInfraPipeline.groovy
//
// Usage from a Jenkinsfile:
//   @Library('jenkins-shared-lib-fresh') _
//   runFullInfraPipeline(
//       AWS_CREDENTIALS_ID: 'aws-jenkins-creds',
//       SSH_CREDENTIALS_ID: 'ansible-green-ssh-key'
//   )
//
def call(Map config = [:]) {
    def nodeLabel        = config.get('NODE_LABEL', 'nginx-agent')
    def slackChannel     = config.get('SLACK_CHANNEL_NAME', 'jenkins-notification')
    def environmentName  = config.get('ENVIRONMENT', 'prod')
    def terraformRepoUrl = config.get('TERRAFORM_REPO_URL', 'https://github.com/priyanshubanwala1222-png/Terraform-Nginx-Task-Code.git')
    def ansibleRepoUrl   = config.get('ANSIBLE_REPO_URL', 'https://github.com/priyanshubanwala1222-png/Ansible-Nginx-Task-Code.git')
    def branch           = config.get('BRANCH', 'main')
    def ansibleBasePath  = config.get('ANSIBLE_CODE_BASE_PATH', 'nginx-role')
    def awsCredentialsId = config.get('AWS_CREDENTIALS_ID', 'aws-jenkins-creds')
    def sshCredentialsId = config.get('SSH_CREDENTIALS_ID', 'ansible-green-ssh-key')
    def runApprovalGate  = config.get('KEEP_APPROVAL_STAGE', true).toString().toBoolean()
    def actionMessage    = config.get('ACTION_MESSAGE', "Provisioning infra + deploying Nginx to ${environmentName}")

    pipeline {
        agent { label "${nodeLabel}" }

        parameters {
            choice(
                name: 'TF_ACTION',
                choices: ['apply', 'destroy'],
                description: 'apply = create/update infra and install nginx. destroy = tear everything down (skips the Ansible stages).'
            )
        }

        stages {

            stage('Checkout Terraform Repo') {
                steps {
                    cleanWs()
                    echo "Cloning Terraform infra from ${terraformRepoUrl} [${branch}]..."
                    dir('terraform') {
                        checkout([$class: 'GitSCM',
                            branches: [[name: "*/${branch}"]],
                            userRemoteConfigs: [[url: terraformRepoUrl]]
                        ])
                    }
                }
            }

            stage('Terraform Init & Plan') {
                steps {
                    dir('terraform') {
                        withCredentials([usernamePassword(credentialsId: awsCredentialsId,
                                                           usernameVariable: 'AWS_ACCESS_KEY_ID',
                                                           passwordVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                            sh """

                                export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID}"
                                export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY}"

                                terraform init -input=false
                                if [ "$TF_ACTION" = "apply" ]; then
                                    terraform plan -input=false -out=tfplan.out
                                else
                                    terraform plan -input=false -destroy -out=tfplan.out
                                fi
                            """
                        }
                    }
                }
            }

            stage('Approval') {
                when { expression { return runApprovalGate } }
                steps {
                    slackSend(channel: slackChannel, color: '#FFFF00',
                        message: "PAUSED: terraform ${params.TF_ACTION} for ${environmentName} awaiting approval: ${env.BUILD_URL}"
                    )
                    input message: "Approve terraform ${params.TF_ACTION} for ${environmentName}?", ok: "Proceed"
                }
            }

            stage('Terraform Apply / Destroy') {
                steps {
                    dir('terraform') {
                        withCredentials([usernamePassword(credentialsId: awsCredentialsId,
                                                           usernameVariable: 'AWS_ACCESS_KEY_ID',
                                                           passwordVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                            sh """
                                export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID}"
                                export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY}"
                                terraform apply -input=false -auto-approve tfplan.out
                            """
                        }
                    }
                }
            }

            stage('Capture Terraform Outputs') {
                when { expression { return params.TF_ACTION == 'apply' } }
                steps {
                    dir('terraform') {
                        script {
                            env.BASTION_PUBLIC_IP = sh(script: 'terraform output -raw bastion-public-ip', returnStdout: true).trim()
                        }
                        echo "Bastion public IP : ${env.BASTION_PUBLIC_IP}"
                    }
                }
            }

            stage('Checkout Ansible Repo') {
                when { expression { return params.TF_ACTION == 'apply' } }
                steps {
                    echo "Cloning Ansible configuration from ${ansibleRepoUrl} [${branch}]..."
                    dir('ansible') {
                        checkout([$class: 'GitSCM',
                            branches: [[name: "*/${branch}"]],
                            userRemoteConfigs: [[url: ansibleRepoUrl]]
                        ])
                    }
                }
            }

            stage('Install Ansible Dependencies') {
                when { expression { return params.TF_ACTION == 'apply' } }
                steps {
                    dir("ansible/${ansibleBasePath}") {
                        sh '''
                            python3 -m pip install --quiet boto3 botocore \
                                || python3 -m pip install --quiet --break-system-packages boto3 botocore
                            ansible-galaxy collection install -r requirements.yml
                        '''
                    }
                }
            }

            stage('Wait for instances to be reachable') {
                when { expression { return params.TF_ACTION == 'apply' } }
                steps {
                    sshagent([sshCredentialsId]) {
                        sh """
                            echo "Waiting for the bastion to accept SSH..."
                            for i in $(seq 1 20); do
                                ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=5 \
                                    ubuntu@${env.BASTION_PUBLIC_IP} "echo bastion ready" && break
                                sleep 10
                            done
                        """
                    }
                    sleep(time: 45, unit: 'SECONDS')
                }
            }

            stage('Run Ansible Playbook') {
                when { expression { return params.TF_ACTION == 'apply' } }
                steps {
                    dir("ansible/${ansibleBasePath}") {
                        withCredentials([usernamePassword(credentialsId: awsCredentialsId,
                                                           usernameVariable: 'AWS_ACCESS_KEY_ID',
                                                           passwordVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                            sshagent([sshCredentialsId]) {
                                sh """
                                    export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID}"
                                    export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY}"
                                    ansible-playbook -i inventories/aws_ec2.yml site.yml
                                """
                            }
                        }
                    }
                }
            }
        }

        post {
            success {
                script {
                    def msg = (params.TF_ACTION == 'apply') 
                        ? "SUCCESS: ${actionMessage}. Build #${env.BUILD_NUMBER} (${env.BUILD_URL})" 
                        : "SUCCESS: infra for ${environmentName} destroyed. Build #${env.BUILD_NUMBER} (${env.BUILD_URL})"
                    slackSend(channel: slackChannel, color: '#00FF00', message: msg)
                }
            }
            failure {
                slackSend(channel: slackChannel, color: '#ff0000',
                    message: "FAILURE: ${actionMessage} (${params.TF_ACTION}) failed. Check logs: ${env.BUILD_URL}"
                )
            }
        }
    }
}
