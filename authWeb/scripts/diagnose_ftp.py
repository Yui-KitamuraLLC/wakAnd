"""Read-only PASV/EPSV comparison; never log credentials or remote contents."""
import ftplib
import os


class ExtendedPassiveFTP(ftplib.FTP):
    def makepasv(self):
        return ftplib.parse229(self.sendcmd('EPSV'), self.sock.getpeername())


for strategy, client_class in [('PASV', ftplib.FTP), ('EPSV', ExtendedPassiveFTP)]:
    for filename in ['ffftp.ja.txt', '.wakand-auth-sync-state.json']:
        count = [0]
        def consume(data):
            count[0] += len(data)
        try:
            with client_class(timeout=15) as ftp:
                ftp.connect('yui.eng.pro', 21)
                ftp.login(os.environ['FTP_USER'], os.environ['FTP_PASSWORD'])
                ftp.cwd('/android/wakAnd/')
                result = ftp.retrbinary('RETR ' + filename, consume)
                print(f'{strategy} {filename}: OK code={result[:3]} bytes={count[0]}')
        except ftplib.error_perm as error:
            print(f'{strategy} {filename}: FTP code={str(error)[:3]}')
        except Exception as error:
            print(f'{strategy} {filename}: {type(error).__name__} errno={getattr(error, "errno", None)}')
