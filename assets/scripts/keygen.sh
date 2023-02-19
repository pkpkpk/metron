if [ ! -f ~/.ssh/id_rsa.pub ]; then
  ssh-keygen -b 2048 -t rsa -f ~/.ssh/id_rsa -q -N ""
fi

ssh-keyscan github.com >> .ssh/known_hosts

cat ~/.ssh/id_rsa.pub
