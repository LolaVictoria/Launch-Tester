import http from 'k6/http';

const VUS = Number(__ENV.VUS || 10);
const DURATION = __ENV.DURATION || '10s';

export const options = {
  stages: [
    { duration: '2s', target: VUS },
    { duration: DURATION, target: VUS },
  ],
};

export default function () {
  http.get(`${__ENV.TARGET_URL}${__ENV.PATH_TO_HIT}`);
}
