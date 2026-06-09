const http = require('http');
const https = require('https');

const PORT = 3001;

const server = http.createServer((req, res) => {
    const reqUrl = new URL(req.url, `http://localhost:${PORT}`);

    if (reqUrl.pathname === '/stream') {
        const targetUrl = reqUrl.searchParams.get('url');
        if (!targetUrl) {
            res.writeHead(400);
            res.end('Missing url parameter');
            return;
        }

        const get = targetUrl.startsWith('https') ? https.get : http.get;
        const forwardHeaders = { 'User-Agent': 'Mozilla/5.0' };
        if (req.headers.range) forwardHeaders['Range'] = req.headers.range;
        if (req.headers['icy-metadata']) forwardHeaders['Icy-Metadata'] = '0';

        const makeRequest = (url) => {
            get(url, { headers: forwardHeaders }, (proxyRes) => {
                // Follow redirects
                if (proxyRes.statusCode >= 300 && proxyRes.statusCode < 400 && proxyRes.headers.location) {
                    makeRequest(proxyRes.headers.location);
                    return;
                }
                // Forward headers that ExoPlayer needs
                const resHeaders = {};
                for (const [k, v] of Object.entries(proxyRes.headers)) {
                    if (v !== undefined) resHeaders[k] = v;
                }
                resHeaders['Access-Control-Allow-Origin'] = '*';
                res.writeHead(proxyRes.statusCode, resHeaders);
                proxyRes.pipe(res);
            }).on('error', () => {
                if (!res.headersSent) { res.writeHead(502); res.end(); }
            });
        };
        makeRequest(targetUrl);
    } else {
        res.writeHead(404);
        res.end();
    }
});

server.listen(PORT, () => console.log('Audio relay on http://localhost:' + PORT));
