import base64, json, sys
from nacl.signing import SigningKey
from cryptography.hazmat.primitives.asymmetric import rsa, padding
from cryptography.hazmat.primitives import hashes, serialization
out = {}
seed = bytes(range(32))
sk = SigningKey(seed)
secret_b64 = base64.b64encode(seed + bytes(sk.verify_key)).decode()   # 64-byte form, as the portal may hand out
msgs = []
for ts, method, path in [("1793134000000", "GET", "/v1/account/balances"), ("1793134000001", "POST", "/v1/orders"),
                         ("1793134999999", "POST", "/v1/order/abc/cancel")]:
    sig = base64.b64encode(sk.sign(f"{ts}{method}{path}".encode()).signature).decode()
    msgs.append({"timestamp": ts, "method": method, "path": path, "signature": sig})
out["ed25519"] = {"secret_key_b64": secret_b64, "seed_b64": base64.b64encode(seed).decode(), "cases": msgs}
key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.TraditionalOpenSSL, serialization.NoEncryption()).decode()
pem8 = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()).decode()
pub = key.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()
pre = "1793134000000GET/trade-api/v2/portfolio/balance"
sig = key.sign(pre.encode(), padding.PSS(mgf=padding.MGF1(hashes.SHA256()), salt_length=padding.PSS.DIGEST_LENGTH), hashes.SHA256())
out["rsa_pss"] = {"pkcs1_pem": pem, "pkcs8_pem": pem8, "public_pem": pub, "preimage": pre,
                  "python_signature": base64.b64encode(sig).decode()}
json.dump(out, open(sys.argv[1], "w"), indent=1)
