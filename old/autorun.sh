#!/bin/bash

# Activate virtual environment
source ~/insectenv/bin/activate

# Move into the project
cd ~/insect-cloud || exit

echo "Starting Insect Cloud server and client..."

# Start picture server
python server.py &
PID1=$!

# Start camera client
python client.py &
PID2=$!

echo "server.py PID: $PID1"
echo "client.py PID (client): $PID2"

# Keep script alive so children do not die
wait
