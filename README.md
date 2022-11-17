# paas-patch-dashboard

Note : The Code is not the built with all the best practises but it does the purpose what its built for very well.

The code is built and sits in GCR. You need to create a job in Cloud Run using that image and a job in CloudScheduler to trigger that job as per your schedule. The Scheduler triggers the Cloud Run job which gathers all the information and then write it in BQ , from where you can visualise it in datastudio as per your need (Ex - https://datastudio.google.com/reporting/ea7e1ccf-f241-4e6f-8300-55358af92e54). Also, you can schedule daily emails from Cloud Studio.

![image](https://user-images.githubusercontent.com/19226494/197113177-5728d6ac-63a8-4fcb-8da9-f44560f40158.png)


More details in the documentation shared seperately .
    
   
# gcp_paas_patch_dashboard
# gcp_paas_patch_dashboard
# gcp_paas_patch_dashboard
