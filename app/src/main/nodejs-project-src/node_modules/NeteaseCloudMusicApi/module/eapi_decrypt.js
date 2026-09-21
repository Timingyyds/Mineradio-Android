const { eapiResDecrypt, eapiReqDecrypt } = require('../util/crypto')
const createOption = require('../util/option.js')

module.exports = async (query, request) => {
  const hexString = query.hexString.replace(/\s/g, '')
  const isReq = query.isReq !== 'false'

  if (!hexString) {
    return {
      status: 400,
      body: {
        code: 400,
        message: 'hex string is required',
      },
    }
  }

  let data
  if (isReq) {
    data = eapiReqDecrypt(hexString)
  } else {
    data = eapiResDecrypt(hexString) || eapiResDecrypt(hexString, true)
  }

  return {
    status: 200,
    body: {
      code: 200,
      data: data,
    },
  }
}
