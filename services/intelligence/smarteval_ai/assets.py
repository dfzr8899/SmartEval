import hashlib
import hmac
import json
import os
from pathlib import Path
from uuid import UUID, uuid4


class Assets:
    """Private immutable objects addressed by tenant, purpose and identifier."""

    def __init__(self, directory: str):
        self.root = Path(directory).resolve()
        self.client = None
        self.bucket = None

    @classmethod
    def configured(cls, directory, endpoint=None):
        assets = cls(directory)
        if endpoint is None:
            endpoint = os.getenv("SMARTEVAL_S3_ENDPOINT", "")
        if endpoint:
            import boto3
            from botocore.config import Config

            assets.bucket = os.environ["SMARTEVAL_S3_BUCKET"]
            key, secret = os.environ["SMARTEVAL_S3_ACCESS_KEY"], os.environ["SMARTEVAL_S3_SECRET_KEY"]
            if not all((assets.bucket, key, secret)):
                raise ValueError("S3 bucket and service credentials are required")
            assets.client = boto3.client("s3", endpoint_url=endpoint, region_name=os.getenv("SMARTEVAL_S3_REGION", "us-east-1"),
                aws_access_key_id=key, aws_secret_access_key=secret,
                config=Config(signature_version="s3v4", s3={"addressing_style": "path"}, connect_timeout=4, read_timeout=8,
                              request_checksum_calculation="when_required", response_checksum_validation="when_required",
                              retries={"total_max_attempts": 2}))
        return assets

    @staticmethod
    def key(tenant: UUID, asset: UUID, purpose: str, extension: str = "json") -> str:
        return f"tenants/{UUID(str(tenant))}/{purpose}/{UUID(str(asset))}.{extension}"

    def path(self, tenant: UUID, asset: UUID, purpose: str, extension: str = "json") -> Path:
        return self.root / self.key(tenant, asset, purpose, extension)

    def read_bytes(self, key, limit):
        if self.client is None:
            with (self.root / key).open("rb") as stream:
                data = stream.read(limit + 1)
        else:
            from botocore.exceptions import BotoCoreError, ClientError

            try:
                result = self.client.get_object(Bucket=self.bucket, Key=key)
                with result["Body"] as stream:
                    data = stream.read(limit + 1)
            except ClientError as error:
                if error.response["ResponseMetadata"]["HTTPStatusCode"] == 404:
                    raise FileNotFoundError("Asset does not exist") from error
                raise OSError("Object storage read failed") from error
            except BotoCoreError as error:
                raise OSError("Object storage read failed") from error
        if len(data) > limit:
            raise ValueError("Asset exceeds maximum size")
        return data

    def readiness(self):
        key = f"health/{uuid4()}.txt"
        marker = uuid4().hex.encode("ascii")
        result = {"backend": "s3" if self.client else "filesystem", "write": False, "read": None, "cleanup": None}
        path = self.root / key
        try:
            if self.client is None:
                path.parent.mkdir(parents=True, exist_ok=True)
                with path.open("xb") as stream:
                    stream.write(marker)
            else:
                self.client.put_object(Bucket=self.bucket, Key=key, Body=marker, IfNoneMatch="*")
        except Exception:
            return result
        result["write"] = True
        try:
            result["read"] = self.read_bytes(key, len(marker)) == marker
        except (OSError, ValueError):
            result["read"] = False
        finally:
            try:
                if self.client is None:
                    path.unlink()
                else:
                    self.client.delete_object(Bucket=self.bucket, Key=key)
                result["cleanup"] = True
            except Exception:
                result["cleanup"] = False
        return result

    def write_bytes(self, tenant, value, purpose, extension):
        asset = uuid4()
        key = self.key(tenant, asset, purpose, extension)
        if self.client is None:
            path = self.root / key
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open("xb") as stream:
                stream.write(value)
        else:
            from botocore.exceptions import BotoCoreError, ClientError

            try:
                self.client.put_object(Bucket=self.bucket, Key=key, Body=value, IfNoneMatch="*")
            except (BotoCoreError, ClientError) as error:
                raise OSError("Object storage write failed") from error
        return {"asset_id": str(asset), "sha256": hashlib.sha256(value).hexdigest()}

    def read(self, tenant, reference, purposes, limit=20 * 1024 * 1024):
        asset_id = UUID(str(reference["asset_id"]))
        for purpose, extension in purposes:
            try:
                raw = self.read_bytes(self.key(tenant, asset_id, purpose, extension), limit)
            except FileNotFoundError:
                continue
            if not hmac.compare_digest(hashlib.sha256(raw).hexdigest(), reference["sha256"]):
                raise ValueError("Asset does not match its authorized digest")
            return raw
        raise FileNotFoundError("Asset does not exist")

    def read_json(self, tenant, reference, *purposes):
        return json.loads(self.read(tenant, reference, [(x, "json") for x in purposes]))

    def write_json(self, tenant, value, purpose):
        raw = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        return self.write_bytes(tenant, raw, purpose, "json")
