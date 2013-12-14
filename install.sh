DBNAME="$1"
shift

dbnames () {
  sudo -u postgres psql -x -l | grep ^Name | awk '{print $3}'
}

if dbnames | grep -q "$DBNAME"; then
  echo "Using Databse: '$DBNAME'"
else
  echo "$0 dbname filename.sql"
  echo ""
  echo "Error: $DBNAME does not exist, the following databases exist on this host:"
  echo ""
  for n in $(dbnames); do
    echo "  $n"
  done
  echo ""
  exit 1
fi

while :; do
  FNAME="$1"
  shift
  if [ -z "$FNAME" ]; then
    break
  fi

  if [ ! -f "$FNAME" ]; then
    echo ""
    echo "Error: $FNAME does not exist!"
    echo ""
    exit 1
  fi

  echo ""
  echo "Executing: $FNAME"
  echo ""
  time sudo -u postgres psql "$DBNAME" -f "$FNAME"
  echo ""
done
