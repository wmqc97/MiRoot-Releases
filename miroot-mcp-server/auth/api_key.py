from functools import wraps
from flask import request, jsonify
from config import Config


def require_api_key(f):
    '''API_KEY auth decorator: validate X-API-Key header'''
    @wraps(f)
    def decorated(*args, **kwargs):
        if not Config.AUTH_ENABLED:
            return f(*args, **kwargs)

        api_key = request.headers.get("X-API-Key")
        if not api_key or api_key != Config.API_KEY:
            return jsonify({
                "jsonrpc": "2.0",
                "error": {
                    "code": -32001,
                    "message": "Unauthorized: invalid or missing API Key"
                }
            }), 401
        return f(*args, **kwargs)
    return decorated


class OAuthHandler:
    '''OAuth2.0 handler stub (reserved for MiClaw platform)'''

    @staticmethod
    def authorize():
        return jsonify({
            "message": "OAuth authorization endpoint - not yet implemented",
            "status": "unavailable"
        }), 501

    @staticmethod
    def token():
        return jsonify({
            "message": "OAuth token endpoint - not yet implemented",
            "status": "unavailable"
        }), 501
