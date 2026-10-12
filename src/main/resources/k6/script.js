import http from 'k6/http';

const VUS = Number(__ENV.VUS || 50);

export const options = {
  stages: [
    { duration: '15s', target: Math.max(1, Math.round(VUS / 5)) },
    { duration: '15s', target: VUS },
  ],
};

export default function () {
  http.get(`${__ENV.TARGET_URL}${__ENV.PATH_TO_HIT}`);
}
