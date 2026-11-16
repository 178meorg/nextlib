FROM debian:trixie-slim
ARG DEBIAN_MIRROR=deb.debian.org
RUN sed -i "s/deb.debian.org/${DEBIAN_MIRROR}/g" /etc/apt/sources.list.d/debian.sources \
    && apt-get update && apt-get install -y --no-install-recommends samba \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --no-create-home nextlib \
    && printf 'nextlib-test\nnextlib-test\n' | smbpasswd -s -a nextlib
COPY smb.conf /etc/samba/smb.conf
EXPOSE 445
CMD ["smbd", "--foreground", "--no-process-group", "--debug-stdout"]
