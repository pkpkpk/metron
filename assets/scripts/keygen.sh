
# need to put these in home because .ssh is not persisted
# subsequent calls to ssh need to specify this key with '-i id_rsa'
# and git commands need to specify GIT_SSH_COMMAND

if [ ! -f ~.ssh/id_rsa.pub ]; then
  ssh-keygen -b 2048 -t rsa -f .ssh/id_rsa -q -N ""
fi

ssh-keyscan github.com >> .ssh/known_hosts

chmod +r .ssh/id_rsa

cat .ssh/id_rsa.pub
