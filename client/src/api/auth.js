import http from './http'

export const login = async (credentials) => {
  const response = await http.post('/user/login', credentials)
  return response.data
}

export const register = async (account) => {
  const response = await http.post('/user/register', account)
  return response.data
}
