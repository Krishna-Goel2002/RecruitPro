import './fonts'
import tokens from './tokens.json'
import Mark from './Mark'
import type { Brand, BrandTokens } from '../../types'

const brand: Brand = { ...(tokens as BrandTokens), Mark }
export default brand
