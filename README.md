# Deploy

## To build
````
lein uberjar
docker build .

docker save [image id] > aplomb.tar
````
scp it to the server.
## To run
````
docker load < aplomb.tar

# for sanity you should tag the image with docker tag [image id] [name]

docker run -d --restart="on-failure" -e "UPSTREAMS=http://172.16.8.11|2,http://172.16.8.12|2" -e "HOST_ADDR=foo.bar" -e "PORT=3000" -e "API_SECRET=foo" -e "DEV=false" -p 3000:3000 [image id]
````

Obvously change the upstreams and the host addr.
