#!/bin/bash

# Activate virtual environment
source ~/insectenv/bin/activate

# Move into the project
cd ~/insect-cloud || exit

echo "Starting Insect Cloud servers and client..."

# Start picture server
python server-picture.py &
PID1=$!

# Start labeler server
python server-labeler.py &
PID2=$!

# Start GPIO/camera client (the button + GPS + BME280 script)
python client.py &
PID3=$!

echo "server-picture.py PID: $PID1"
echo "server-labeler.py PID: $PID2"
echo "client.py PID (client): $PID3"

# Keep script alive so children do not die
wait
