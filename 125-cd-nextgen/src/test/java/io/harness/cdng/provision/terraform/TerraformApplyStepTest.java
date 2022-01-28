/*
 * Copyright 2021 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.cdng.provision.terraform;

import static io.harness.rule.OwnerRule.NAMAN_TALAYCHA;
import static io.harness.rule.OwnerRule.NGONZALEZ;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.powermock.api.mockito.PowerMockito.doReturn;
import static org.powermock.api.mockito.PowerMockito.mockStatic;

import io.harness.CategoryTest;
import io.harness.annotations.dev.HarnessTeam;
import io.harness.annotations.dev.OwnedBy;
import io.harness.beans.EnvironmentType;
import io.harness.category.element.UnitTests;
import io.harness.cdng.manifest.yaml.ArtifactoryStoreConfig;
import io.harness.cdng.manifest.yaml.GithubStore;
import io.harness.cdng.manifest.yaml.storeConfig.StoreConfig;
import io.harness.cdng.manifest.yaml.storeConfig.StoreConfigType;
import io.harness.cdng.manifest.yaml.storeConfig.StoreConfigWrapper;
import io.harness.connector.ConnectorInfoDTO;
import io.harness.delegate.beans.TaskData;
import io.harness.delegate.beans.connector.ConnectorType;
import io.harness.delegate.beans.connector.artifactoryconnector.ArtifactoryAuthType;
import io.harness.delegate.beans.connector.artifactoryconnector.ArtifactoryAuthenticationDTO;
import io.harness.delegate.beans.connector.artifactoryconnector.ArtifactoryConnectorDTO;
import io.harness.delegate.beans.connector.artifactoryconnector.ArtifactoryUsernamePasswordAuthDTO;
import io.harness.delegate.beans.connector.scm.GitAuthType;
import io.harness.delegate.beans.connector.scm.GitConnectionType;
import io.harness.delegate.beans.connector.scm.genericgitconnector.GitConfigDTO;
import io.harness.delegate.beans.logstreaming.UnitProgressData;
import io.harness.delegate.beans.storeconfig.ArtifactoryStoreDelegateConfig;
import io.harness.delegate.beans.storeconfig.FetchType;
import io.harness.delegate.beans.storeconfig.GitStoreDelegateConfig;
import io.harness.delegate.task.git.GitFetchFilesConfig;
import io.harness.delegate.task.terraform.TFTaskType;
import io.harness.delegate.task.terraform.TerraformTaskNGParameters;
import io.harness.delegate.task.terraform.TerraformTaskNGResponse;
import io.harness.encryption.SecretRefData;
import io.harness.exception.InvalidRequestException;
import io.harness.exception.WingsException;
import io.harness.logging.CommandExecutionStatus;
import io.harness.logging.UnitProgress;
import io.harness.ng.core.EntityDetail;
import io.harness.plancreator.steps.common.StepElementParameters;
import io.harness.pms.contracts.ambiance.Ambiance;
import io.harness.pms.contracts.execution.Status;
import io.harness.pms.contracts.execution.tasks.TaskRequest;
import io.harness.pms.rbac.PipelineRbacHelper;
import io.harness.pms.sdk.core.steps.io.StepInputPackage;
import io.harness.pms.sdk.core.steps.io.StepResponse;
import io.harness.pms.yaml.ParameterField;
import io.harness.rule.Owner;
import io.harness.serializer.KryoSerializer;
import io.harness.steps.StepHelper;
import io.harness.steps.StepUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.Builder;
import lombok.Data;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

@RunWith(PowerMockRunner.class)
@PrepareForTest({StepUtils.class})
@OwnedBy(HarnessTeam.CDP)
public class TerraformApplyStepTest extends CategoryTest {
  @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

  @Mock private KryoSerializer kryoSerializer;
  @Mock private TerraformStepHelper terraformStepHelper;
  @Mock private TerraformConfigHelper terraformConfigHelper;
  @Mock private PipelineRbacHelper pipelineRbacHelper;
  @InjectMocks private TerraformApplyStep terraformApplyStep;
  @Mock private StepHelper stepHelper;

  private Ambiance getAmbiance() {
    return Ambiance.newBuilder()
        .putSetupAbstractions("accountId", "test-account")
        .putSetupAbstractions("projectIdentifier", "test-project")
        .putSetupAbstractions("orgIdentifier", "test-org")
        .build();
  }
  @Data
  @Builder
  private static class gitStoreConfig {
    private String branch;
    private FetchType fetchType;
    private ParameterField<String> folderPath;
    private ParameterField<String> connectoref;
  }

  @Data
  @Builder
  private static class artifactoryStoreConfig {
    private String repositoryPath;
    private String artifactoryName;
    private String version;
    private String connectorRef;
  }

  @Captor ArgumentCaptor<List<EntityDetail>> captor;

  @Test
  @Owner(developers = NAMAN_TALAYCHA)
  @Category(UnitTests.class)
  public void testValidateResourcesWithGithubStore() {
    Ambiance ambiance = getAmbiance();
    gitStoreConfig gitStoreConfigFiles = gitStoreConfig.builder()
                                             .branch("master")
                                             .fetchType(FetchType.BRANCH)
                                             .folderPath(ParameterField.createValueField("Config/"))
                                             .connectoref(ParameterField.createValueField("terraform"))
                                             .build();
    gitStoreConfig gitStoreVarFiles = gitStoreConfig.builder()
                                          .branch("master")
                                          .fetchType(FetchType.BRANCH)
                                          .folderPath(ParameterField.createValueField("VarFiles/"))
                                          .connectoref(ParameterField.createValueField("terraform"))
                                          .build();

    TerraformApplyStepParameters applyStepParameters =
        generateStepPlan(StoreConfigType.GITHUB, gitStoreConfigFiles, gitStoreVarFiles);
    StepElementParameters stepElementParameters = StepElementParameters.builder().spec(applyStepParameters).build();
    terraformApplyStep.validateResources(ambiance, stepElementParameters);
    verify(pipelineRbacHelper, times(1)).checkRuntimePermissions(eq(ambiance), captor.capture(), eq(true));

    assertThat("true").isEqualTo("true");
    List<EntityDetail> entityDetails = captor.getValue();
    assertThat(entityDetails.size()).isEqualTo(2);
    assertThat(entityDetails.get(0).getEntityRef().getIdentifier()).isEqualTo("terraform");
    assertThat(entityDetails.get(0).getEntityRef().getAccountIdentifier()).isEqualTo("test-account");
    assertThat(entityDetails.get(1).getEntityRef().getIdentifier()).isEqualTo("terraform");
    assertThat(entityDetails.get(1).getEntityRef().getAccountIdentifier()).isEqualTo("test-account");
  }

  @Test
  @Owner(developers = NGONZALEZ)
  @Category(UnitTests.class)
  public void testValidateResourcesWithArtifactoryStore() {
    Ambiance ambiance = getAmbiance();
    artifactoryStoreConfig artifactoryStoreConfigFiles = artifactoryStoreConfig.builder()
                                                             .artifactoryName("artifactoryConfigFiles")
                                                             .connectorRef("connectorRef")
                                                             .repositoryPath("repositoryPath")
                                                             .version("1.0.0")
                                                             .build();
    artifactoryStoreConfig artifactoryStoreVarFiles = artifactoryStoreConfig.builder()
                                                          .artifactoryName("artifactoryVarFiles")
                                                          .connectorRef("connectorRef2")
                                                          .repositoryPath("repositoryPathtoVars")
                                                          .version("1.0.2")
                                                          .build();

    TerraformApplyStepParameters applyStepParameters =
        generateStepPlan(StoreConfigType.ARTIFACTORY, artifactoryStoreConfigFiles, artifactoryStoreVarFiles);
    StepElementParameters stepElementParameters = StepElementParameters.builder().spec(applyStepParameters).build();
    terraformApplyStep.validateResources(ambiance, stepElementParameters);
    verify(pipelineRbacHelper, times(1)).checkRuntimePermissions(eq(ambiance), captor.capture(), eq(true));

    assertThat("true").isEqualTo("true");
    List<EntityDetail> entityDetails = captor.getValue();
    assertThat(entityDetails.size()).isEqualTo(2);
    assertThat(entityDetails.get(0).getEntityRef().getIdentifier()).isEqualTo("connectorRef");
    assertThat(entityDetails.get(0).getEntityRef().getAccountIdentifier()).isEqualTo("test-account");
    assertThat(entityDetails.get(1).getEntityRef().getIdentifier()).isEqualTo("connectorRef2");
    assertThat(entityDetails.get(1).getEntityRef().getAccountIdentifier()).isEqualTo("test-account");
  }

  @Test(expected = NullPointerException.class)
  @Owner(developers = NAMAN_TALAYCHA)
  @Category(UnitTests.class)
  public void testValidateResourcesNegativeScenario() {
    Ambiance ambiance = getAmbiance();
    TerraformApplyStepParameters applyStepParameters =
        TerraformApplyStepParameters.infoBuilder()
            .provisionerIdentifier(ParameterField.createValueField("provId_$"))
            .configuration(TerraformStepConfigurationParameters.builder()
                               .type(TerraformStepConfigurationType.INLINE)
                               .spec(TerraformExecutionDataParameters.builder().build())
                               .build())
            .build();
    StepElementParameters stepElementParameters = StepElementParameters.builder().spec(applyStepParameters).build();
    terraformApplyStep.validateResources(ambiance, stepElementParameters);
  }

  @Test
  @Owner(developers = NAMAN_TALAYCHA)
  @Category(UnitTests.class)
  public void testObtainTaskAfterRbacWithGithub() {
    Ambiance ambiance = getAmbiance();
    gitStoreConfig gitStoreConfigFiles = gitStoreConfig.builder()
                                             .branch("master")
                                             .fetchType(FetchType.BRANCH)
                                             .folderPath(ParameterField.createValueField("Config/"))
                                             .connectoref(ParameterField.createValueField("terraform"))
                                             .build();
    gitStoreConfig gitStoreVarFiles = gitStoreConfig.builder()
                                          .branch("master")
                                          .fetchType(FetchType.BRANCH)
                                          .folderPath(ParameterField.createValueField("VarFiles/"))
                                          .connectoref(ParameterField.createValueField("terraform"))
                                          .build();

    TerraformApplyStepParameters applyStepParameters =
        generateStepPlan(StoreConfigType.GITHUB, gitStoreConfigFiles, gitStoreVarFiles);

    GitConfigDTO gitConfigDTO = GitConfigDTO.builder()
                                    .gitAuthType(GitAuthType.HTTP)
                                    .gitConnectionType(GitConnectionType.ACCOUNT)
                                    .delegateSelectors(Collections.singleton("delegateName"))
                                    .url("https://github.com/wings-software")
                                    .branchName("master")
                                    .build();
    GitStoreDelegateConfig gitStoreDelegateConfig =
        GitStoreDelegateConfig.builder().branch("master").connectorName("terraform").gitConfigDTO(gitConfigDTO).build();
    GitFetchFilesConfig gitFetchFilesConfig = GitFetchFilesConfig.builder()
                                                  .identifier("terraform")
                                                  .gitStoreDelegateConfig(gitStoreDelegateConfig)
                                                  .succeedIfFileNotFound(false)
                                                  .build();
    StepElementParameters stepElementParameters = StepElementParameters.builder().spec(applyStepParameters).build();
    StepInputPackage stepInputPackage = StepInputPackage.builder().build();
    doReturn("test-account/test-org/test-project/Id").when(terraformStepHelper).generateFullIdentifier(any(), any());
    doReturn(gitFetchFilesConfig).when(terraformStepHelper).getGitFetchFilesConfig(any(), any(), any());
    doReturn(EnvironmentType.NON_PROD).when(stepHelper).getEnvironmentType(any());
    mockStatic(StepUtils.class);
    PowerMockito.when(StepUtils.prepareCDTaskRequest(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(TaskRequest.newBuilder().build());
    ArgumentCaptor<TaskData> taskDataArgumentCaptor = ArgumentCaptor.forClass(TaskData.class);
    TaskRequest taskRequest = terraformApplyStep.obtainTaskAfterRbac(ambiance, stepElementParameters, stepInputPackage);
    assertThat(taskRequest).isNotNull();
    PowerMockito.verifyStatic(StepUtils.class, times(1));
    StepUtils.prepareCDTaskRequest(any(), taskDataArgumentCaptor.capture(), any(), any(), any(), any(), any());
    assertThat(taskDataArgumentCaptor.getValue()).isNotNull();
    assertThat(taskDataArgumentCaptor.getValue().getParameters()).isNotNull();
    TerraformTaskNGParameters taskParameters =
        (TerraformTaskNGParameters) taskDataArgumentCaptor.getValue().getParameters()[0];
    assertThat(taskParameters.getTaskType()).isEqualTo(TFTaskType.APPLY);
  }
  @Test
  @Owner(developers = NAMAN_TALAYCHA)
  @Category(UnitTests.class)
  public void testObtainTaskAfterRbacWithArtifactory() {
    Ambiance ambiance = getAmbiance();
    artifactoryStoreConfig artifactoryStoreConfigFiles = artifactoryStoreConfig.builder()
                                                             .artifactoryName("artifactoryConfigFiles")
                                                             .connectorRef("connectorRef")
                                                             .repositoryPath("repositoryPath")
                                                             .version("1.0.0")
                                                             .build();
    artifactoryStoreConfig artifactoryStoreVarFiles = artifactoryStoreConfig.builder()
                                                          .artifactoryName("artifactoryVarFiles")
                                                          .connectorRef("connectorRef2")
                                                          .repositoryPath("repositoryPathtoVars")
                                                          .version("1.0.2")
                                                          .build();

    TerraformApplyStepParameters applyStepParameters =
        generateStepPlan(StoreConfigType.ARTIFACTORY, artifactoryStoreConfigFiles, artifactoryStoreVarFiles);

    ArtifactoryStoreDelegateConfig artifactoryStoreDelegateConfig = createStoreDelegateConfig();
    StepElementParameters stepElementParameters = StepElementParameters.builder().spec(applyStepParameters).build();
    StepInputPackage stepInputPackage = StepInputPackage.builder().build();
    doReturn("test-account/test-org/test-project/Id").when(terraformStepHelper).generateFullIdentifier(any(), any());
    doReturn(artifactoryStoreDelegateConfig)
        .when(terraformStepHelper)
        .getFileFactoryFetchFilesConfig(any(), any(), any());
    doReturn(EnvironmentType.NON_PROD).when(stepHelper).getEnvironmentType(any());
    mockStatic(StepUtils.class);
    PowerMockito.when(StepUtils.prepareCDTaskRequest(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(TaskRequest.newBuilder().build());
    ArgumentCaptor<TaskData> taskDataArgumentCaptor = ArgumentCaptor.forClass(TaskData.class);
    TaskRequest taskRequest = terraformApplyStep.obtainTaskAfterRbac(ambiance, stepElementParameters, stepInputPackage);
    assertThat(taskRequest).isNotNull();
    PowerMockito.verifyStatic(StepUtils.class, times(1));
    StepUtils.prepareCDTaskRequest(any(), taskDataArgumentCaptor.capture(), any(), any(), any(), any(), any());
    assertThat(taskDataArgumentCaptor.getValue()).isNotNull();
    assertThat(taskDataArgumentCaptor.getValue().getParameters()).isNotNull();
    TerraformTaskNGParameters taskParameters =
        (TerraformTaskNGParameters) taskDataArgumentCaptor.getValue().getParameters()[0];
    assertThat(taskParameters.getTaskType()).isEqualTo(TFTaskType.APPLY);
    assertThat(taskParameters.getFileStoreConfigFiles().getConnectorDTO().getConnectorType().toString())
        .isEqualTo(ConnectorType.ARTIFACTORY.toString());
  }
  @Test(expected = NullPointerException.class) // configFile is Absent
  @Owner(developers = NAMAN_TALAYCHA)
  @Category(UnitTests.class)
  public void testObtainTaskAfterRbacNegtiveScenario() {
    Ambiance ambiance = getAmbiance();
    TerraformApplyStepParameters applyStepParameters =
        TerraformApplyStepParameters.infoBuilder()
            .provisionerIdentifier(ParameterField.createValueField("Id"))
            .configuration(TerraformStepConfigurationParameters.builder()
                               .type(TerraformStepConfigurationType.INLINE)
                               .spec(TerraformExecutionDataParameters.builder().build())
                               .build())
            .build();
    GitFetchFilesConfig gitFetchFilesConfig =
        GitFetchFilesConfig.builder().identifier("terraform").succeedIfFileNotFound(false).build();
    StepElementParameters stepElementParameters = StepElementParameters.builder().spec(applyStepParameters).build();
    StepInputPackage stepInputPackage = StepInputPackage.builder().build();
    doReturn("test-account/test-org/test-project/Id").when(terraformStepHelper).generateFullIdentifier(any(), any());
    doReturn(gitFetchFilesConfig).when(terraformStepHelper).getGitFetchFilesConfig(any(), any(), any());
    doReturn(EnvironmentType.NON_PROD).when(stepHelper).getEnvironmentType(any());
    mockStatic(StepUtils.class);
    PowerMockito.when(StepUtils.prepareCDTaskRequest(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(TaskRequest.newBuilder().build());
    terraformApplyStep.obtainTaskAfterRbac(ambiance, stepElementParameters, stepInputPackage);
  }

  @Test
  @Owner(developers = NAMAN_TALAYCHA)
  @Category(UnitTests.class)
  public void testObtainTaskAfterRbacInheritPlan() {
    Ambiance ambiance = getAmbiance();
    TerraformApplyStepParameters applyStepParameters =
        TerraformApplyStepParameters.infoBuilder()
            .provisionerIdentifier(ParameterField.createValueField("Id"))
            .configuration(TerraformStepConfigurationParameters.builder()
                               .type(TerraformStepConfigurationType.INHERIT_FROM_PLAN)
                               .build())
            .build();
    GitConfigDTO gitConfigDTO = GitConfigDTO.builder()
                                    .gitAuthType(GitAuthType.HTTP)
                                    .gitConnectionType(GitConnectionType.ACCOUNT)
                                    .delegateSelectors(Collections.singleton("delegateName"))
                                    .url("https://github.com/wings-software")
                                    .branchName("master")
                                    .build();
    GitStoreDelegateConfig gitStoreDelegateConfig =
        GitStoreDelegateConfig.builder().branch("master").connectorName("terraform").gitConfigDTO(gitConfigDTO).build();
    GitFetchFilesConfig gitFetchFilesConfig = GitFetchFilesConfig.builder()
                                                  .identifier("terraform")
                                                  .gitStoreDelegateConfig(gitStoreDelegateConfig)
                                                  .succeedIfFileNotFound(false)
                                                  .build();
    StepElementParameters stepElementParameters = StepElementParameters.builder().spec(applyStepParameters).build();
    StepInputPackage stepInputPackage = StepInputPackage.builder().build();
    doReturn("test-account/test-org/test-project/Id").when(terraformStepHelper).generateFullIdentifier(any(), any());
    doReturn(gitFetchFilesConfig).when(terraformStepHelper).getGitFetchFilesConfig(any(), any(), any());
    doReturn(EnvironmentType.NON_PROD).when(stepHelper).getEnvironmentType(any());

    mockStatic(StepUtils.class);
    PowerMockito.when(StepUtils.prepareCDTaskRequest(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(TaskRequest.newBuilder().build());
    ArgumentCaptor<TaskData> taskDataArgumentCaptor = ArgumentCaptor.forClass(TaskData.class);

    TerraformInheritOutput inheritOutput =
        TerraformInheritOutput.builder().backendConfig("back-content").workspace("w1").planName("plan").build();
    doReturn(inheritOutput).when(terraformStepHelper).getSavedInheritOutput(any(), any(), any());
    TaskRequest taskRequest = terraformApplyStep.obtainTaskAfterRbac(ambiance, stepElementParameters, stepInputPackage);
    assertThat(taskRequest).isNotNull();
    PowerMockito.verifyStatic(StepUtils.class, times(1));
    StepUtils.prepareCDTaskRequest(any(), taskDataArgumentCaptor.capture(), any(), any(), any(), any(), any());
    assertThat(taskDataArgumentCaptor.getValue()).isNotNull();
    assertThat(taskDataArgumentCaptor.getValue().getParameters()).isNotNull();
    TerraformTaskNGParameters taskParameters =
        (TerraformTaskNGParameters) taskDataArgumentCaptor.getValue().getParameters()[0];
    assertThat(taskParameters.getTaskType()).isEqualTo(TFTaskType.APPLY);
  }

  @Test
  @Owner(developers = NGONZALEZ)
  @Category(UnitTests.class)
  public void testObtainTaskAfterRbacInheritPlanWithArtifactory() {
    Ambiance ambiance = getAmbiance();
    artifactoryStoreConfig artifactoryStoreConfigFiles = artifactoryStoreConfig.builder()
                                                             .artifactoryName("artifactoryConfigFiles")
                                                             .connectorRef("connectorRef")
                                                             .repositoryPath("repositoryPath")
                                                             .version("1.0.0")
                                                             .build();
    artifactoryStoreConfig artifactoryStoreVarFiles = artifactoryStoreConfig.builder()
                                                          .artifactoryName("artifactoryVarFiles")
                                                          .connectorRef("connectorRef2")
                                                          .repositoryPath("repositoryPathtoVars")
                                                          .version("1.0.2")
                                                          .build();

    TerraformApplyStepParameters applyStepParameters =
        generateStepPlan(StoreConfigType.ARTIFACTORY, artifactoryStoreConfigFiles, artifactoryStoreVarFiles);
    StepElementParameters stepElementParameters = StepElementParameters.builder().spec(applyStepParameters).build();

    ArtifactoryStoreDelegateConfig artifactoryStoreDelegateConfig = createStoreDelegateConfig();
    StepInputPackage stepInputPackage = StepInputPackage.builder().build();
    doReturn("test-account/test-org/test-project/Id").when(terraformStepHelper).generateFullIdentifier(any(), any());
    doReturn(artifactoryStoreDelegateConfig)
        .when(terraformStepHelper)
        .getFileFactoryFetchFilesConfig(any(), any(), any());
    doReturn(EnvironmentType.NON_PROD).when(stepHelper).getEnvironmentType(any());

    mockStatic(StepUtils.class);
    PowerMockito.when(StepUtils.prepareCDTaskRequest(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(TaskRequest.newBuilder().build());
    ArgumentCaptor<TaskData> taskDataArgumentCaptor = ArgumentCaptor.forClass(TaskData.class);

    TerraformInheritOutput inheritOutput =
        TerraformInheritOutput.builder().backendConfig("back-content").workspace("w1").planName("plan").build();
    doReturn(inheritOutput).when(terraformStepHelper).getSavedInheritOutput(any(), any(), any());
    TaskRequest taskRequest = terraformApplyStep.obtainTaskAfterRbac(ambiance, stepElementParameters, stepInputPackage);
    assertThat(taskRequest).isNotNull();
    PowerMockito.verifyStatic(StepUtils.class, times(1));
    StepUtils.prepareCDTaskRequest(any(), taskDataArgumentCaptor.capture(), any(), any(), any(), any(), any());
    assertThat(taskDataArgumentCaptor.getValue()).isNotNull();
    assertThat(taskDataArgumentCaptor.getValue().getParameters()).isNotNull();
    TerraformTaskNGParameters taskParameters =
        (TerraformTaskNGParameters) taskDataArgumentCaptor.getValue().getParameters()[0];
    assertThat(taskParameters.getTaskType()).isEqualTo(TFTaskType.APPLY);
  }

  @Test // Unknown configuration Type: [InheritFromApply]
  @Owner(developers = NAMAN_TALAYCHA)
  @Category(UnitTests.class)
  public void testObtainTaskAfterRbacNegativeScenario() {
    Ambiance ambiance = getAmbiance();
    TerraformApplyStepParameters applyStepParameters =
        TerraformApplyStepParameters.infoBuilder()
            .provisionerIdentifier(ParameterField.createValueField("Id"))
            .configuration(TerraformStepConfigurationParameters.builder()
                               .type(TerraformStepConfigurationType.INHERIT_FROM_APPLY)
                               .build())
            .build();
    GitFetchFilesConfig gitFetchFilesConfig =
        GitFetchFilesConfig.builder().identifier("terraform").succeedIfFileNotFound(false).build();
    StepElementParameters stepElementParameters = StepElementParameters.builder().spec(applyStepParameters).build();
    StepInputPackage stepInputPackage = StepInputPackage.builder().build();
    doReturn("test-account/test-org/test-project/Id").when(terraformStepHelper).generateFullIdentifier(any(), any());
    doReturn(gitFetchFilesConfig).when(terraformStepHelper).getGitFetchFilesConfig(any(), any(), any());
    doReturn(EnvironmentType.NON_PROD).when(stepHelper).getEnvironmentType(any());

    mockStatic(StepUtils.class);
    PowerMockito.when(StepUtils.prepareCDTaskRequest(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(TaskRequest.newBuilder().build());

    TerraformInheritOutput inheritOutput =
        TerraformInheritOutput.builder().backendConfig("back-content").workspace("w1").planName("plan").build();
    doReturn(inheritOutput).when(terraformStepHelper).getSavedInheritOutput(any(), any(), any());
    String message = "Unknown configuration Type: [InheritFromApply]";
    try {
      terraformApplyStep.obtainTaskAfterRbac(ambiance, stepElementParameters, stepInputPackage);
    } catch (InvalidRequestException invalidRequestException) {
      assertThat(invalidRequestException.getMessage()).isEqualTo(message);
    }
  }

  @Test
  @Owner(developers = NAMAN_TALAYCHA)
  @Category(UnitTests.class)
  public void testHandleTaskResultWithSecurityContext_Inherited() throws Exception {
    Ambiance ambiance = getAmbiance();
    TerraformApplyStepParameters applyStepParameters =
        TerraformApplyStepParameters.infoBuilder()
            .provisionerIdentifier(ParameterField.createValueField("Id"))
            .configuration(TerraformStepConfigurationParameters.builder()
                               .type(TerraformStepConfigurationType.INHERIT_FROM_PLAN)
                               .build())
            .build();
    GitConfigDTO gitConfigDTO = GitConfigDTO.builder()
                                    .gitAuthType(GitAuthType.HTTP)
                                    .gitConnectionType(GitConnectionType.ACCOUNT)
                                    .delegateSelectors(Collections.singleton("delegateName"))
                                    .url("https://github.com/wings-software")
                                    .branchName("master")
                                    .build();
    GitStoreDelegateConfig gitStoreDelegateConfig =
        GitStoreDelegateConfig.builder().branch("master").connectorName("terraform").gitConfigDTO(gitConfigDTO).build();
    GitFetchFilesConfig gitFetchFilesConfig = GitFetchFilesConfig.builder()
                                                  .identifier("terraform")
                                                  .gitStoreDelegateConfig(gitStoreDelegateConfig)
                                                  .succeedIfFileNotFound(false)
                                                  .build();
    StepElementParameters stepElementParameters = StepElementParameters.builder().spec(applyStepParameters).build();
    doReturn("test-account/test-org/test-project/Id").when(terraformStepHelper).generateFullIdentifier(any(), any());
    doReturn(gitFetchFilesConfig).when(terraformStepHelper).getGitFetchFilesConfig(any(), any(), any());
    TerraformInheritOutput inheritOutput =
        TerraformInheritOutput.builder().backendConfig("back-content").workspace("w1").planName("plan").build();
    doReturn(inheritOutput).when(terraformStepHelper).getSavedInheritOutput(any(), any(), any());
    List<UnitProgress> unitProgresses = Collections.singletonList(UnitProgress.newBuilder().build());
    UnitProgressData unitProgressData = UnitProgressData.builder().unitProgresses(unitProgresses).build();
    TerraformTaskNGResponse terraformTaskNGResponse = TerraformTaskNGResponse.builder()
                                                          .commandExecutionStatus(CommandExecutionStatus.SUCCESS)
                                                          .unitProgressData(unitProgressData)
                                                          .build();
    StepResponse stepResponse = terraformApplyStep.handleTaskResultWithSecurityContext(
        ambiance, stepElementParameters, () -> terraformTaskNGResponse);
    assertThat(stepResponse.getStatus()).isEqualTo(Status.SUCCEEDED);
    assertThat(stepResponse.getStepOutcomes()).isNotNull();
  }

  @Test
  @Owner(developers = NAMAN_TALAYCHA)
  @Category(UnitTests.class)
  public void testHandleTaskResultWithSecurityContext_Inline() throws Exception {
    Ambiance ambiance = getAmbiance();
    TerraformApplyStepParameters applyStepParameters =
        TerraformApplyStepParameters.infoBuilder()
            .provisionerIdentifier(ParameterField.createValueField("Id"))
            .configuration(
                TerraformStepConfigurationParameters.builder().type(TerraformStepConfigurationType.INLINE).build())
            .build();
    GitConfigDTO gitConfigDTO = GitConfigDTO.builder()
                                    .gitAuthType(GitAuthType.HTTP)
                                    .gitConnectionType(GitConnectionType.ACCOUNT)
                                    .delegateSelectors(Collections.singleton("delegateName"))
                                    .url("https://github.com/wings-software")
                                    .branchName("master")
                                    .build();
    GitStoreDelegateConfig gitStoreDelegateConfig =
        GitStoreDelegateConfig.builder().branch("master").connectorName("terraform").gitConfigDTO(gitConfigDTO).build();
    GitFetchFilesConfig gitFetchFilesConfig = GitFetchFilesConfig.builder()
                                                  .identifier("terraform")
                                                  .gitStoreDelegateConfig(gitStoreDelegateConfig)
                                                  .succeedIfFileNotFound(false)
                                                  .build();
    StepElementParameters stepElementParameters = StepElementParameters.builder().spec(applyStepParameters).build();
    doReturn("test-account/test-org/test-project/Id").when(terraformStepHelper).generateFullIdentifier(any(), any());
    doReturn(gitFetchFilesConfig).when(terraformStepHelper).getGitFetchFilesConfig(any(), any(), any());
    TerraformInheritOutput inheritOutput =
        TerraformInheritOutput.builder().backendConfig("back-content").workspace("w1").planName("plan").build();
    doReturn(inheritOutput).when(terraformStepHelper).getSavedInheritOutput(any(), any(), any());
    List<UnitProgress> unitProgresses = Collections.singletonList(UnitProgress.newBuilder().build());
    UnitProgressData unitProgressData = UnitProgressData.builder().unitProgresses(unitProgresses).build();
    TerraformTaskNGResponse terraformTaskNGResponse = TerraformTaskNGResponse.builder()
                                                          .commandExecutionStatus(CommandExecutionStatus.SUCCESS)
                                                          .unitProgressData(unitProgressData)
                                                          .build();
    StepResponse stepResponse = terraformApplyStep.handleTaskResultWithSecurityContext(
        ambiance, stepElementParameters, () -> terraformTaskNGResponse);
    assertThat(stepResponse.getStatus()).isEqualTo(Status.SUCCEEDED);
    assertThat(stepResponse.getStepOutcomes()).isNotNull();
  }

  @Test
  @Owner(developers = NAMAN_TALAYCHA)
  @Category(UnitTests.class)
  public void testHandleTaskResultWithSecurityContextAndArtifactoryAsStore_Inline() throws Exception {
    Ambiance ambiance = getAmbiance();
    TerraformApplyStepParameters applyStepParameters =
        TerraformApplyStepParameters.infoBuilder()
            .provisionerIdentifier(ParameterField.createValueField("Id"))
            .configuration(
                TerraformStepConfigurationParameters.builder().type(TerraformStepConfigurationType.INLINE).build())
            .build();
    ArtifactoryStoreDelegateConfig artifactoryStoreDelegateConfig = createStoreDelegateConfig();

    StepElementParameters stepElementParameters = StepElementParameters.builder().spec(applyStepParameters).build();
    doReturn("test-account/test-org/test-project/Id").when(terraformStepHelper).generateFullIdentifier(any(), any());
    doReturn(artifactoryStoreDelegateConfig)
        .when(terraformStepHelper)
        .getFileFactoryFetchFilesConfig(any(), any(), any());
    TerraformInheritOutput inheritOutput =
        TerraformInheritOutput.builder().backendConfig("back-content").workspace("w1").planName("plan").build();
    doReturn(inheritOutput).when(terraformStepHelper).getSavedInheritOutput(any(), any(), any());
    List<UnitProgress> unitProgresses = Collections.singletonList(UnitProgress.newBuilder().build());
    UnitProgressData unitProgressData = UnitProgressData.builder().unitProgresses(unitProgresses).build();
    TerraformTaskNGResponse terraformTaskNGResponse = TerraformTaskNGResponse.builder()
                                                          .commandExecutionStatus(CommandExecutionStatus.SUCCESS)
                                                          .unitProgressData(unitProgressData)
                                                          .build();
    StepResponse stepResponse = terraformApplyStep.handleTaskResultWithSecurityContext(
        ambiance, stepElementParameters, () -> terraformTaskNGResponse);
    assertThat(stepResponse.getStatus()).isEqualTo(Status.SUCCEEDED);
    assertThat(stepResponse.getStepOutcomes()).isNotNull();
  }

  @Test
  @Owner(developers = NAMAN_TALAYCHA)
  @Category(UnitTests.class)
  public void testGetStepParametersClass() {
    assertThat(terraformApplyStep.getStepParametersClass()).isEqualTo(StepElementParameters.class);
  }
  @Test
  @Owner(developers = NAMAN_TALAYCHA)
  @Category(UnitTests.class)
  public void testHandleTaskResultWithSecurityContextNegativeScenario() throws Exception {
    Ambiance ambiance = getAmbiance();
    TerraformApplyStepParameters applyStepParameters =
        TerraformApplyStepParameters.infoBuilder()
            .provisionerIdentifier(ParameterField.createValueField("Id"))
            .configuration(TerraformStepConfigurationParameters.builder()
                               .type(TerraformStepConfigurationType.INHERIT_FROM_APPLY)
                               .build())
            .build();
    GitConfigDTO gitConfigDTO = GitConfigDTO.builder()
                                    .gitAuthType(GitAuthType.HTTP)
                                    .gitConnectionType(GitConnectionType.ACCOUNT)
                                    .delegateSelectors(Collections.singleton("delegateName"))
                                    .url("https://github.com/wings-software")
                                    .branchName("master")
                                    .build();
    GitStoreDelegateConfig gitStoreDelegateConfig =
        GitStoreDelegateConfig.builder().branch("master").connectorName("terraform").gitConfigDTO(gitConfigDTO).build();
    GitFetchFilesConfig gitFetchFilesConfig = GitFetchFilesConfig.builder()
                                                  .identifier("terraform")
                                                  .gitStoreDelegateConfig(gitStoreDelegateConfig)
                                                  .succeedIfFileNotFound(false)
                                                  .build();
    StepElementParameters stepElementParameters = StepElementParameters.builder().spec(applyStepParameters).build();
    doReturn("test-account/test-org/test-project/Id").when(terraformStepHelper).generateFullIdentifier(any(), any());
    doReturn(gitFetchFilesConfig).when(terraformStepHelper).getGitFetchFilesConfig(any(), any(), any());
    TerraformInheritOutput inheritOutput =
        TerraformInheritOutput.builder().backendConfig("back-content").workspace("w1").planName("plan").build();
    doReturn(inheritOutput).when(terraformStepHelper).getSavedInheritOutput(any(), any(), any());
    List<UnitProgress> unitProgresses = Collections.singletonList(UnitProgress.newBuilder().build());
    UnitProgressData unitProgressData = UnitProgressData.builder().unitProgresses(unitProgresses).build();
    TerraformTaskNGResponse terraformTaskNGResponse = TerraformTaskNGResponse.builder()
                                                          .commandExecutionStatus(CommandExecutionStatus.SUCCESS)
                                                          .unitProgressData(unitProgressData)
                                                          .build();
    String message = "Unknown configuration Type: [InheritFromApply]";
    try {
      terraformApplyStep.handleTaskResultWithSecurityContext(
          ambiance, stepElementParameters, () -> terraformTaskNGResponse);
    } catch (InvalidRequestException invalidRequestException) {
      assertThat(invalidRequestException.getMessage()).isEqualTo(message);
    }
  }

  @Test // Different Status
  @Owner(developers = NAMAN_TALAYCHA)
  @Category(UnitTests.class)
  public void handleTaskResultWithSecurityContextDifferentStatus() throws Exception {
    Ambiance ambiance = getAmbiance();
    TerraformApplyStepParameters applyStepParameters =
        TerraformApplyStepParameters.infoBuilder()
            .provisionerIdentifier(ParameterField.createValueField("Id"))
            .configuration(TerraformStepConfigurationParameters.builder()
                               .type(TerraformStepConfigurationType.INHERIT_FROM_PLAN)
                               .build())
            .build();
    GitConfigDTO gitConfigDTO = GitConfigDTO.builder()
                                    .gitAuthType(GitAuthType.HTTP)
                                    .gitConnectionType(GitConnectionType.ACCOUNT)
                                    .delegateSelectors(Collections.singleton("delegateName"))
                                    .url("https://github.com/wings-software")
                                    .branchName("master")
                                    .build();
    GitStoreDelegateConfig gitStoreDelegateConfig =
        GitStoreDelegateConfig.builder().branch("master").connectorName("terraform").gitConfigDTO(gitConfigDTO).build();
    GitFetchFilesConfig gitFetchFilesConfig = GitFetchFilesConfig.builder()
                                                  .identifier("terraform")
                                                  .gitStoreDelegateConfig(gitStoreDelegateConfig)
                                                  .succeedIfFileNotFound(false)
                                                  .build();
    StepElementParameters stepElementParameters = StepElementParameters.builder().spec(applyStepParameters).build();
    doReturn("test-account/test-org/test-project/Id").when(terraformStepHelper).generateFullIdentifier(any(), any());
    doReturn(gitFetchFilesConfig).when(terraformStepHelper).getGitFetchFilesConfig(any(), any(), any());
    TerraformInheritOutput inheritOutput =
        TerraformInheritOutput.builder().backendConfig("back-content").workspace("w1").planName("plan").build();
    doReturn(inheritOutput).when(terraformStepHelper).getSavedInheritOutput(any(), any(), any());
    List<UnitProgress> unitProgresses = Collections.singletonList(UnitProgress.newBuilder().build());
    UnitProgressData unitProgressData = UnitProgressData.builder().unitProgresses(unitProgresses).build();

    TerraformTaskNGResponse terraformTaskNGResponseFailure = TerraformTaskNGResponse.builder()
                                                                 .commandExecutionStatus(CommandExecutionStatus.FAILURE)
                                                                 .unitProgressData(unitProgressData)
                                                                 .build();
    StepResponse stepResponse = terraformApplyStep.handleTaskResultWithSecurityContext(
        ambiance, stepElementParameters, () -> terraformTaskNGResponseFailure);
    assertThat(stepResponse.getStatus()).isEqualTo(Status.FAILED);
    assertThat(stepResponse.getStepOutcomes()).isNotNull();

    TerraformTaskNGResponse terraformTaskNGResponseRunning = TerraformTaskNGResponse.builder()
                                                                 .commandExecutionStatus(CommandExecutionStatus.RUNNING)
                                                                 .unitProgressData(unitProgressData)
                                                                 .build();
    stepResponse = terraformApplyStep.handleTaskResultWithSecurityContext(
        ambiance, stepElementParameters, () -> terraformTaskNGResponseRunning);
    assertThat(stepResponse.getStatus()).isEqualTo(Status.RUNNING);
    assertThat(stepResponse.getStepOutcomes()).isNotNull();

    TerraformTaskNGResponse terraformTaskNGResponseQueued = TerraformTaskNGResponse.builder()
                                                                .commandExecutionStatus(CommandExecutionStatus.QUEUED)
                                                                .unitProgressData(unitProgressData)
                                                                .build();
    stepResponse = terraformApplyStep.handleTaskResultWithSecurityContext(
        ambiance, stepElementParameters, () -> terraformTaskNGResponseQueued);
    assertThat(stepResponse.getStatus()).isEqualTo(Status.QUEUED);
    assertThat(stepResponse.getStepOutcomes()).isNotNull();
    String message =
        String.format("Unhandled type CommandExecutionStatus: " + CommandExecutionStatus.SKIPPED, WingsException.USER);
    try {
      TerraformTaskNGResponse terraformTaskNGResponseSkipped =
          TerraformTaskNGResponse.builder()
              .commandExecutionStatus(CommandExecutionStatus.SKIPPED)
              .unitProgressData(unitProgressData)
              .build();
      terraformApplyStep.handleTaskResultWithSecurityContext(
          ambiance, stepElementParameters, () -> terraformTaskNGResponseSkipped);
    } catch (InvalidRequestException invalidRequestException) {
      assertThat(invalidRequestException.getMessage()).isEqualTo(message);
    }
  }
  private TerraformApplyStepParameters generateStepPlan(
      StoreConfigType storeType, Object storeConfigFilesParam, Object varStoreConfigFilesParam) {
    StoreConfig storeConfigFiles;
    StoreConfig storeVarFiles;
    TerraformConfigFilesWrapper configFilesWrapper = new TerraformConfigFilesWrapper();
    RemoteTerraformVarFileSpec remoteTerraformVarFileSpec = new RemoteTerraformVarFileSpec();
    switch (storeType) {
      case GIT:
      case GITHUB:
      case GITLAB:
      case BITBUCKET:
        // Create the store file for the terraform files
        gitStoreConfig gitStoreConfigFiles = (gitStoreConfig) storeConfigFilesParam;
        storeConfigFiles =
            GithubStore.builder()
                .branch(ParameterField.createValueField(gitStoreConfigFiles.branch))
                .gitFetchType(gitStoreConfigFiles.fetchType)
                .folderPath(ParameterField.createValueField(gitStoreConfigFiles.folderPath.getValue()))
                .connectorRef(ParameterField.createValueField(gitStoreConfigFiles.connectoref.getValue()))
                .build();
        configFilesWrapper.setStore(StoreConfigWrapper.builder().spec(storeConfigFiles).type(storeType).build());
        // Create the store file for the terraform variables
        gitStoreConfig gitStoreVarFiles = (gitStoreConfig) varStoreConfigFilesParam;
        storeVarFiles = GithubStore.builder()
                            .branch(ParameterField.createValueField(gitStoreVarFiles.branch))
                            .gitFetchType(gitStoreVarFiles.fetchType)
                            .folderPath(ParameterField.createValueField(gitStoreVarFiles.folderPath.getValue()))
                            .connectorRef(ParameterField.createValueField(gitStoreVarFiles.connectoref.getValue()))
                            .build();
        remoteTerraformVarFileSpec.setStore(StoreConfigWrapper.builder().spec(storeVarFiles).type(storeType).build());
        break;
      case ARTIFACTORY:
        // Create the store file for the terraform files
        artifactoryStoreConfig artifactoryStoreConfigFiles = (artifactoryStoreConfig) storeConfigFilesParam;
        storeConfigFiles =
            ArtifactoryStoreConfig.builder()
                .repositoryPath(ParameterField.createValueField(artifactoryStoreConfigFiles.repositoryPath))
                .artifactName(ParameterField.createValueField(artifactoryStoreConfigFiles.artifactoryName))
                .version(ParameterField.createValueField(artifactoryStoreConfigFiles.version))
                .connectorRef(ParameterField.createValueField(artifactoryStoreConfigFiles.connectorRef))
                .build();
        configFilesWrapper.setStore(StoreConfigWrapper.builder().spec(storeConfigFiles).type(storeType).build());
        // Create the store file for the terraform variables
        artifactoryStoreConfig artifactoryStoreVarFiles = (artifactoryStoreConfig) varStoreConfigFilesParam;
        storeVarFiles = ArtifactoryStoreConfig.builder()
                            .repositoryPath(ParameterField.createValueField(artifactoryStoreVarFiles.repositoryPath))
                            .artifactName(ParameterField.createValueField(artifactoryStoreVarFiles.artifactoryName))
                            .version(ParameterField.createValueField(artifactoryStoreVarFiles.version))
                            .connectorRef(ParameterField.createValueField(artifactoryStoreVarFiles.connectorRef))
                            .build();
        remoteTerraformVarFileSpec.setStore(StoreConfigWrapper.builder().spec(storeVarFiles).type(storeType).build());
        break;
      default:
        break;
    }
    InlineTerraformVarFileSpec inlineTerraformVarFileSpec = new InlineTerraformVarFileSpec();
    inlineTerraformVarFileSpec.setContent(ParameterField.createValueField("var-content"));
    InlineTerraformBackendConfigSpec inlineTerraformBackendConfigSpec = new InlineTerraformBackendConfigSpec();
    inlineTerraformBackendConfigSpec.setContent(ParameterField.createValueField("back-content"));
    TerraformBackendConfig terraformBackendConfig = new TerraformBackendConfig();
    terraformBackendConfig.setTerraformBackendConfigSpec(inlineTerraformBackendConfigSpec);
    LinkedHashMap<String, TerraformVarFile> varFilesMap = new LinkedHashMap<>();
    varFilesMap.put("var-file-01",
        TerraformVarFile.builder().identifier("var-file-01").type("Inline").spec(inlineTerraformVarFileSpec).build());
    varFilesMap.put("var-file-02",
        TerraformVarFile.builder().identifier("var-file-02").type("Remote").spec(remoteTerraformVarFileSpec).build());
    return TerraformApplyStepParameters.infoBuilder()
        .provisionerIdentifier(ParameterField.createValueField("provId_$"))
        .configuration(TerraformStepConfigurationParameters.builder()
                           .type(TerraformStepConfigurationType.INLINE)
                           .spec(TerraformExecutionDataParameters.builder()
                                     .configFiles(configFilesWrapper)
                                     .varFiles(varFilesMap)
                                     .build())
                           .build())
        .build();
  }

  private ArtifactoryStoreDelegateConfig createStoreDelegateConfig() {
    // Create auth with user and password
    char[] password = {'r', 's', 't', 'u', 'v'};
    ArtifactoryAuthenticationDTO artifactoryAuthenticationDTO =
        ArtifactoryAuthenticationDTO.builder()
            .authType(ArtifactoryAuthType.USER_PASSWORD)
            .credentials(ArtifactoryUsernamePasswordAuthDTO.builder()
                             .username("username")
                             .passwordRef(SecretRefData.builder().decryptedValue(password).build())
                             .build())
            .build();

    // Create DTO connector
    ArtifactoryConnectorDTO artifactoryConnectorDTO = ArtifactoryConnectorDTO.builder()
                                                          .artifactoryServerUrl("http://artifactory.com")
                                                          .auth(artifactoryAuthenticationDTO)
                                                          .delegateSelectors(Collections.singleton("delegateSelector"))
                                                          .build();
    ConnectorInfoDTO connectorInfoDTO = ConnectorInfoDTO.builder()
                                            .connectorType(ConnectorType.ARTIFACTORY)
                                            .identifier("connectorRef")
                                            .name("connectorName")
                                            .connectorConfig(artifactoryConnectorDTO)
                                            .build();

    return ArtifactoryStoreDelegateConfig.builder()
        .artifactName("artifactName")
        .repositoryPath("repositoryPath")
        .version("1.0.0")
        .connectorDTO(connectorInfoDTO)
        .succeedIfFileNotFound(false)
        .build();
  }
}
