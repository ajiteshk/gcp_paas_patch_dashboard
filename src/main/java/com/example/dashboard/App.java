package com.example.dashboard;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.services.container.Container;
import com.google.api.services.container.Container.Projects.Locations.GetServerConfig;
import com.google.api.services.container.model.Cluster;
import com.google.api.services.container.model.ListClustersResponse;
import com.google.api.services.container.model.ServerConfig;
import com.google.api.services.sqladmin.SQLAdmin;
import com.google.api.services.sqladmin.SQLAdmin.Instances.Get;
import com.google.api.services.sqladmin.model.DatabaseInstance;
import com.google.api.services.sqladmin.model.InstancesListResponse;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryError;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.InsertAllRequest;
import com.google.cloud.bigquery.InsertAllResponse;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableResult;
import com.google.cloud.resourcemanager.Project;
import com.google.cloud.resourcemanager.ResourceManager;
import com.google.cloud.resourcemanager.ResourceManagerOptions;

public class App {
    private static InsertAllRequest build;

    static BigQuery bigquery;
    static Map<String, String> gkeVersionMap = new HashMap<>();
    static Map<String, String> cloudSQLVersionMap = new HashMap<>();

    private static Get instanceDetails;
    private static SQLAdmin sqlAdminService;
    static List<String> projectList;

    private static ServerConfig response;
    private static Map<String, Boolean> rapidVersionMap = new HashMap<>();
    private static Map<String, Boolean> regularVersionMap = new HashMap<>();
    private static Map<String, Boolean> stableVersionMap = new HashMap<>();
    private static Properties prop = new Properties();
    private static String BQ_URL = System.getenv("BQ_URL");

    public static void main(String[] args) throws Exception {
        loadProperties();
        projectList = getProjectList();
        if (projectList != null && !projectList.isEmpty()) {
            for (String projectId : projectList) {
                loadGKEVersions(projectId);
            }

            for (Map.Entry<String, Boolean> entry : stableVersionMap.entrySet())
                System.out.println("Key = " + entry.getKey() +
                        ", Value = " + entry.getValue());
            bigquery = BigQueryOptions.getDefaultInstance().getService();
            readDataFromBQ();
            // Load Everything in memory Cache

            for (String projectId : projectList) {
                // get GKE Clusters
                List<Map<String, Object>> rowList = getGkeCluster(projectId);
                if (rowList != null && !rowList.isEmpty()) {
                    writeToBQ(rowList);
                }

                // // Get CloudSQl instances
                System.out.println("Querying for CloudSQL in " + projectId);
                List<Map<String, Object>> cloudSQLList = getCloudSQLInstances(projectId);
                if (cloudSQLList != null && !cloudSQLList.isEmpty()) {
                    writeToBQ(cloudSQLList);
                }

                // // Get Memory Store instance
            }
        }else{
            System.out.println("no project exists");
        }
    }

    private static void loadProperties() throws Exception {
        try (InputStream input = App.class.getClassLoader().getResourceAsStream("config.properties")) {
            // load a properties file
            prop.load(input);
        } catch (Exception e) {
            System.out.println("Error in reading Properties file");
            throw e;
        }
    }

    private static List<String> getProjectList() throws FileNotFoundException, IOException {
        List<String> projectList = new ArrayList<String>();
        //ResourceManager resourceManager = ResourceManagerOptions.getDefaultInstance().getService();
        System.out.println(new File(".").getAbsolutePath());
        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
        ResourceManager resourceManager = ResourceManagerOptions.newBuilder()
    .setCredentials(credentials)
    .build()
    .getService();
        Iterator<Project> projectIterator = resourceManager.list().iterateAll().iterator();
        System.out.println("Projects I can view:");
        while (projectIterator.hasNext()) {
            Project next = projectIterator.next();
            if (next.getState() != null && next.getState().name().equals("ACTIVE")) {
                System.out.println(next.getName());
                projectList.add(next.getProjectId());
            }
        }
        return projectList;
    }

    private static void loadGKEVersions(String projectId) throws IOException, GeneralSecurityException {
        System.out.println("Load GKE");
        Container containerService = Utility.createContainerService();
        List<String> regionList = new ArrayList<>();
        regionList.add("asia-south1");
        regionList.add("asia-south2");
        for (String region : regionList) {
            String inputParam = "projects/" + projectId + "/locations/" + region;
            GetServerConfig request = containerService.projects().locations().getServerConfig(inputParam);
            response = request.execute();
            JSONObject jsonObject = new JSONObject(response);
            JSONArray channels = jsonObject.getJSONArray("channels");

            for (int i = 0; i < channels.length(); i++) {

                // store each object in JSONObject
                JSONObject explrObject = channels.getJSONObject(i);

                String defaultVersion = (String) explrObject.get("defaultVersion");
                if (explrObject.get("channel").equals("RAPID")) {
                    addToGkeVersionSet(explrObject, rapidVersionMap, defaultVersion);
                } else if (explrObject.get("channel").equals("REGULAR")) {
                    addToGkeVersionSet(explrObject, regularVersionMap, defaultVersion);
                } else if (explrObject.get("channel").equals("STABLE")) {
                    addToGkeVersionSet(explrObject, stableVersionMap, defaultVersion);
                }
            }
        }
        System.out.println("CheckPoint");
    }

    private static void addToGkeVersionSet(JSONObject explrObject, Map<String, Boolean> versionMap,
            String defaultVersion) {
        JSONArray validVersions = explrObject.getJSONArray("validVersions");
        String validVersion = null;

        for (int j = 0; j < validVersions.length(); j++) {
            Boolean defaultVer = null;
            validVersion = (String) validVersions.get(j);

            if (validVersion.equals(defaultVersion)) {
                defaultVer = true;
            }
            versionMap.put(validVersion, defaultVer);
        }
    }

    private static List<Map<String, Object>> getGkeCluster(String projectId)
            throws IOException, GeneralSecurityException {

        Container containerService = Utility.createContainerService();
        Container.Projects.Zones.Clusters.List request = containerService.projects().zones().clusters().list(projectId,
                "-");

        ListClustersResponse response = request.execute();
        List<Map<String, Object>> rowList = new ArrayList<>();
        if (response != null && !response.isEmpty()) {
            // System.out.println(response);

            for (Cluster cluster : response.getClusters()) {
                Map<String, Object> rowContent = new HashMap<>();
                System.out.println("Cluster" + cluster.toPrettyString());
                System.out.println("Cluster Name " + cluster.getName());
                System.out.println("Cluster Master Version " + cluster.getCurrentMasterVersion());
                System.out.println("Cluster NodePool Version " + cluster.getCurrentNodeVersion());
                String currentMasterVersion = cluster.getCurrentMasterVersion();
                String currentNodeVersion = cluster.getCurrentNodeVersion();
                String name = cluster.getName();

                boolean hasEntry = false;
                JSONObject jsonObject = new JSONObject(cluster);
                JSONObject releaseChannelJson = jsonObject.getJSONObject("releaseChannel");
                String releaseChannel = releaseChannelJson.getString("channel");
                StringBuffer validVersions = new StringBuffer();

                System.out.println("Release Channel is" + releaseChannel);
                if (releaseChannel.equals("REGULAR")) {
                    validVersions = getValidVersions(currentMasterVersion, regularVersionMap);
                } else if (releaseChannel.equals("RAPID")) {
                    validVersions = getValidVersions(currentMasterVersion, rapidVersionMap);
                } else if (releaseChannel.equals("STABLE")) {
                    validVersions = getValidVersions(currentMasterVersion, stableVersionMap);
                }

                if (gkeVersionMap.containsKey(projectId + name)) {
                    if (gkeVersionMap.get(projectId + name).equals(currentMasterVersion + "$" + currentNodeVersion)) {
                        hasEntry = true;
                        System.out.println(name + " has already an Entry, hence not adding it");
                    }
                }
                if (!hasEntry) {
                    System.out.println("Adding Name" + name);
                    rowContent.put("Project_Id", projectId);
                    rowContent.put("Component", "GKE");
                    rowContent.put("AvailableUpdates", validVersions.toString());

                    rowContent.put("MasterNodeVersion", currentMasterVersion);

                    rowContent.put("NodePoolVersion", currentNodeVersion);
                    rowContent.put("Version", "");
                    rowContent.put("UpdatedTs", new DateTime(new Date()));
                    rowContent.put("CreatedTs", new DateTime(new Date()));
                    if (cluster.getMaintenancePolicy() != null && cluster.getMaintenancePolicy().getWindow() != null) {
                        if (cluster.getMaintenancePolicy().getWindow().getRecurringWindow() != null) {
                            rowContent.put("RecurringMaintenaceWindow",
                                    cluster.getMaintenancePolicy().getWindow().getRecurringWindow().toPrettyString());
                        }
                        if (cluster.getMaintenancePolicy().getWindow().getMaintenanceExclusions() != null) {
                            rowContent.put("MaintenanceExclustions",
                                    cluster.getMaintenancePolicy().getWindow().getMaintenanceExclusions().toString());
                        }
                    }
                    rowContent.put("Name", name);
                    if (cluster.getResourceLabels() != null && !cluster.getResourceLabels().isEmpty()) {
                        rowContent.put("labels", cluster.getResourceLabels().toString());
                    }

                    rowList.add(rowContent);
                }
            }

        } else {
            System.out.println("No GKE Cluster Exists in Project :" + projectId);
        }
        return rowList;

    }

    private static StringBuffer getValidVersions(String currentMasterVersion, Map<String, Boolean> versionMap) {
        StringBuffer validVersions = new StringBuffer();
        for (Map.Entry<String, Boolean> entry : versionMap.entrySet()) {
            System.out.println("Key is " + entry.getKey() + " and Value " + entry.getValue());
            // String cu
            if (Utility.compare(entry.getKey(), currentMasterVersion) >= 0) {
                if (entry.getValue() != null && entry.getValue() == Boolean.TRUE) {
                    validVersions.append(entry.getKey() + "[DEFAULT]" + "\n");
                } else {
                    validVersions.append(entry.getKey() + "\n");
                }

            }
            System.out.println("valid Version " + validVersions);
        }
        return validVersions;
    }

    private static void writeToBQ(List<Map<String, Object>> rowList) {

        TableId tableId = TableId.of("paas_dashboard", "version_details");

        for (Map<String, Object> rowContent : rowList) {
            build = InsertAllRequest.newBuilder(tableId).addRow(rowContent).build();
            InsertAllResponse response = bigquery.insertAll(build);

            if (response.hasErrors()) {
                // If any of the insertions failed, this lets you inspect the errors
                for (Map.Entry<Long, List<BigQueryError>> entry : response.getInsertErrors().entrySet()) {
                    System.out.println("Response error: \n" + entry.getValue());
                }
            } else {
                System.out.println("-------------- Rows successfully inserted into table ---------------------");
            }
        }

    }

    private static List<Map<String, Object>> getCloudSQLInstances(String projectId)
            throws GeneralSecurityException, IOException {
        boolean hasEntry = false;
        sqlAdminService = createSqlAdminService();
        InstancesListResponse resp = sqlAdminService.instances().list(projectId).execute();
        List<DatabaseInstance> instanceList = resp.getItems();
        Map<String, Object> rowContent = new HashMap<>();
        List<Map<String, Object>> rowList = new ArrayList<>();
        String instanceName=null;
        if (instanceList != null && !instanceList.isEmpty()) {

            for (DatabaseInstance instance : instanceList) {
                rowContent = new HashMap<>();
                System.out.println("Instance Name"+instance.getName());
                instanceName = instance.getName();
                JSONObject databaseJSON = new JSONObject(instance);

                System.out.println(instance.getName());
                instanceDetails = sqlAdminService.instances().get(projectId, instanceName);

                DatabaseInstance response = instanceDetails.execute();
                JSONObject jsonObject = new JSONObject(response);
                System.out.println(jsonObject.toString());
                String maintenanceVersion = jsonObject.getString("maintenanceVersion");
                System.out.println(maintenanceVersion);
                String key = projectId + instanceName;
                System.out.println("CloudSQL key is " + key);
                if (cloudSQLVersionMap.containsKey(key)) {
                    if (cloudSQLVersionMap.get(key).equals(maintenanceVersion)) {
                        hasEntry = true;
                        System.out.println(key + " has already an Entry, hence not adding it");
                    }
                }
                if (!hasEntry) {
                    System.out.println("Adding Name" + instanceName);
                    if (response.getSettings().getMaintenanceWindow() != null) {
                        rowContent.put("RecurringMaintenaceWindow",
                                response.getSettings().getMaintenanceWindow().toPrettyString());
                    }
                    rowContent.put("Project_Id", projectId);
                    rowContent.put("Component", "CloudSQL");
                    System.out.println("databaseJSON" + databaseJSON.toString());
                    try {
                        if (databaseJSON != null &&
                                databaseJSON.get("availableMaintenanceVersions") != null) {
                            System.out.println("databaseJSON-----" + databaseJSON.get("availableMaintenanceVersions"));
                            rowContent.put("AvailableUpdates", databaseJSON.get("availableMaintenanceVersions"));
                        }
                    } catch (JSONException e) {
                        System.out.println(e);
                    }

                    rowContent.put("Version", maintenanceVersion);
                    rowContent.put("UpdatedTs", new DateTime(new Date()));
                    rowContent.put("CreatedTs", new DateTime(new Date()));
                    rowContent.put("Name", instanceName);
                    if (response.getSettings() != null && response.getSettings().getUserLabels() != null) {
                        rowContent.put("labels", response.getSettings().getUserLabels().toString());
                    }
                    rowList.add(rowContent);
                }
            }
        } else {
            System.out.println("No CloudSQL Cluster Exists in Project :" + projectId);
        }
        return rowList;
    }

    // public static SQLAdmin createSqlAdminService() throws IOException, GeneralSecurityException {

    //     JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    //     GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
    //     HttpRequestInitializer requestInitializer = new HttpCredentialsAdapter(credentials);
    //     HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();

    //     return new SQLAdmin.Builder(httpTransport, JSON_FACTORY, requestInitializer)
    //             .setApplicationName("Google-PaaS-Dashboard")
    //             .build();
    // }

    public static SQLAdmin createSqlAdminService() throws IOException, GeneralSecurityException {
        HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        JsonFactory jsonFactory = JacksonFactory.getDefaultInstance();
    
        GoogleCredential credential = GoogleCredential.getApplicationDefault();
        if (credential.createScopedRequired()) {
          credential =
              credential.createScoped(Arrays.asList("https://www.googleapis.com/auth/cloud-platform"));
        }
    
        return new SQLAdmin.Builder(httpTransport, jsonFactory, credential)
            .setApplicationName("Google-SQLAdminSample/0.1")
            .build();
      }

    private static void readDataFromBQ() throws InterruptedException {
        QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(
                "SELECT * "
                        + "FROM " + BQ_URL+" order by CreatedTs")
                .setUseLegacySql(false)
                .build();
        System.out.println("Query is"+queryConfig.toString());
        // Create a job ID so that we can safely retry.
        JobId jobId = JobId.of(UUID.randomUUID().toString());
        Job queryJob = bigquery.create(JobInfo.newBuilder(queryConfig).setJobId(jobId).build());

        // Wait for the query to complete.
        queryJob = queryJob.waitFor();

        // Check for errors
        if (queryJob == null) {
            throw new RuntimeException("Job no longer exists");
        } else if (queryJob.getStatus().getError() != null) {
            // You can also look at queryJob.getStatus().getExecutionErrors() for all
            // errors, not just the latest one.
            throw new RuntimeException(queryJob.getStatus().getError().toString());
        } else {
            TableResult result = queryJob.getQueryResults();

            // Print all pages of the results.
            for (FieldValueList row : result.iterateAll()) {
                // String type
                String component = row.get("Component").getStringValue();
                String name = row.get("Name").getStringValue();
                // Record type
                String projectId = row.get("Project_Id").getStringValue();

                if (component.equals("GKE")) {
                    gkeVersionMap.put(projectId + name, row.get("MasterNodeVersion").getStringValue() + "$"
                            + row.get("NodePoolVersion").getStringValue());
                    System.out.println("Adding to GKE Version Map --- " + projectId + name);
                } else if (component.equals("CloudSQL")) {
                    cloudSQLVersionMap.put(projectId + name, row.get("Version").getStringValue());
                    System.out.println("Adding to CloudSQL Version Map --- " + projectId + name);
                }

            }
        }
    }
}