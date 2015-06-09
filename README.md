# Deploy

## To build
````
lein uberjar
docker build .

docker save [image id] > ocpu-balancer.tar
````
scp it to the server.
## To run
````
docker load < ocpu-balancer.tar

docker run -d --restart="on-failure" -e "UPSTREAMS=http://172.16.8.11|2,http://172.16.8.12|2" -e "HOST_ADDR=analysis.doctorevidence.com" -p 3000:3000 [image id]
````

Obvously change the upstreams and the host addr.